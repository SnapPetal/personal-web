package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * CalDAV client for the configured calendar collection.
 *
 * <p>The collection URL is used directly. Radicale collections are already published at that URL,
 * so this client does not discover principals or build provider-specific paths.
 */
@Service
@ConditionalOnProperty(name = "calendar.caldav.enabled", havingValue = "true")
@Slf4j
class CalDavService {

    private final IcsGenerator icsGenerator;
    private final CalendarProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final CalDavAppTokenProvider tokenProvider;
    private final URI calendarCollection;
    private volatile WebClient webClient;

    CalDavService(
            final IcsGenerator icsGenerator,
            final CalendarProperties properties,
            final WebClient.Builder webClientBuilder,
            final CalDavAppTokenProvider tokenProvider) {
        this.icsGenerator = icsGenerator;
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.tokenProvider = tokenProvider;
        this.calendarCollection = calendarCollection(properties.baseUrl(), properties.calendarUrl());
    }

    /**
     * Confirms the configured calendar collection is reachable.
     */
    @Retryable(maxAttempts = 3)
    public void verifyCalendarCollection() {
        final var response = client().method(HttpMethod.valueOf("PROPFIND"))
                .uri(calendarCollection)
                .header("Depth", "0")
                .retrieve()
                .toBodilessEntity()
                .block();

        if (Objects.isNull(response) || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("CalDAV collection is not available at " + calendarCollection);
        }
        log.info("CalDAV collection is available at {}", calendarCollection);
    }

    @Retryable(maxAttempts = 3)
    public String createEvent(final BookingCreatedEvent event) {
        final var calendarUid = "booking-" + event.confirmationCode();
        final var icsContent = icsGenerator.generate(event, properties.organizerEmail(), calendarUid);
        final var eventUri = calendarCollection.resolve(calendarUid + ".ics");

        try {
            client().put()
                    .uri(eventUri)
                    .header(HttpHeaders.CONTENT_TYPE, "text/calendar; charset=utf-8")
                    .header(HttpHeaders.IF_NONE_MATCH, "*")
                    .bodyValue(icsContent.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (final WebClientResponseException exception) {
            if (exception.getStatusCode().value() == 412) {
                log.info("CalDAV event {} already exists, reusing deterministic UID", calendarUid);
                return calendarUid;
            }
            throw exception;
        }

        log.info("Created CalDAV event {} for booking {}", calendarUid, event.confirmationCode());
        return calendarUid;
    }

    @Retryable(maxAttempts = 3)
    public void deleteEvent(final String calendarUid) {
        final var eventUri = calendarCollection.resolve(calendarUid + ".ics");

        try {
            client().delete().uri(eventUri).retrieve().toBodilessEntity().block();
            log.info("Deleted CalDAV event {}", calendarUid);
        } catch (final WebClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                log.info("CalDAV event {} already deleted", calendarUid);
                return;
            }
            throw exception;
        }
    }

    @Retryable(maxAttempts = 3)
    public List<String> listEventResources() {
        final var propfindBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:propfind>
                """;

        final var responseBody = client().method(HttpMethod.valueOf("PROPFIND"))
                .uri(calendarCollection)
                .header("Depth", "1")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(propfindBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseHrefsFromMultistatus(responseBody);
    }

    static URI calendarCollection(final String baseUrl, final String calendarUrl) {
        if (Objects.isNull(baseUrl) || baseUrl.isBlank()) {
            throw new IllegalStateException("calendar.caldav.base-url is required");
        }
        if (Objects.isNull(calendarUrl) || calendarUrl.isBlank()) {
            throw new IllegalStateException("calendar.caldav.calendar-url is required");
        }

        final var normalizedBase = withTrailingSlash(baseUrl);
        final var normalizedCalendar = withTrailingSlash(calendarUrl);
        if (!normalizedCalendar.startsWith(normalizedBase)) {
            throw new IllegalStateException("calendar.caldav.calendar-url must be under calendar.caldav.base-url");
        }
        return URI.create(normalizedCalendar);
    }

    private WebClient client() {
        final var existing = webClient;
        if (Objects.nonNull(existing)) {
            return existing;
        }
        synchronized (this) {
            if (Objects.nonNull(webClient)) {
                return webClient;
            }
            final var username = properties.username();
            if (Objects.isNull(username) || username.isBlank()) {
                throw new IllegalStateException("calendar.caldav.username is required");
            }
            final var password = tokenProvider.bookingAppToken();
            if (Objects.isNull(password) || password.isBlank()) {
                throw new IllegalStateException("CalDAV password is empty");
            }
            final var authHeader = "Basic "
                    + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            webClient = webClientBuilder
                    .baseUrl(withTrailingSlash(properties.baseUrl()))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .build();
            return webClient;
        }
    }

    private static String withTrailingSlash(final String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private List<String> parseHrefsFromMultistatus(final String xml) {
        final var uids = new ArrayList<String>();
        if (Objects.isNull(xml) || xml.isBlank()) {
            return uids;
        }

        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final var builder = factory.newDocumentBuilder();
            final var document = builder.parse(new InputSource(new StringReader(xml)));

            final var hrefNodes = document.getElementsByTagNameNS("DAV:", "href");
            for (int i = 0; i < hrefNodes.getLength(); i++) {
                final var href = ((Element) hrefNodes.item(i)).getTextContent().trim();
                if (href.endsWith(".ics")) {
                    final var fileName = href.substring(href.lastIndexOf('/') + 1);
                    final var uid = fileName.replace(".ics", "");
                    uids.add(uid);
                }
            }
        } catch (final Exception exception) {
            throw new IllegalStateException("Failed to parse CalDAV PROPFIND response", exception);
        }

        return uids;
    }
}
