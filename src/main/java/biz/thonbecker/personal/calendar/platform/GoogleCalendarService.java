package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Calendar API client for the booking calendar.
 *
 * <p>The calendar is found by its name in the owner's calendar list. Event IDs are derived from the
 * booking confirmation code, so a replayed create reuses the same event.
 */
@Service
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@Slf4j
class GoogleCalendarService {

    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String API_BASE_URI = "https://www.googleapis.com/calendar/v3";
    private static final String BOOKING_TIME_ZONE = "America/Chicago";

    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(1);
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CalendarProperties properties;
    private final GoogleCalendarCredentialsProvider credentialsProvider;
    private final WebClient webClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GoogleCalendarCredentials credentials;
    private volatile GoogleAccessToken accessToken;
    private volatile String calendarId;

    @Autowired
    GoogleCalendarService(
            final CalendarProperties properties,
            final GoogleCalendarCredentialsProvider credentialsProvider,
            final WebClient.Builder webClientBuilder) {
        this(properties, credentialsProvider, webClientBuilder, Clock.systemUTC());
    }

    GoogleCalendarService(
            final CalendarProperties properties,
            final GoogleCalendarCredentialsProvider credentialsProvider,
            final WebClient.Builder webClientBuilder,
            final Clock clock) {
        this.properties = properties;
        this.credentialsProvider = credentialsProvider;
        this.webClient = webClientBuilder.clone().build();
        this.clock = clock;
    }

    /**
     * Confirms the authorization works and that exactly one writable calendar has the configured name.
     */
    @Retryable(maxAttempts = 3, noRetryFor = IllegalStateException.class)
    public void verifyCalendar() {
        log.info("Google calendar {} is available", authorized(this::calendarId));
    }

    @Retryable(maxAttempts = 3, noRetryFor = IllegalStateException.class)
    public String createEvent(final BookingCreatedEvent event) {
        final var eventId = eventId(event.confirmationCode());
        final var body = new LinkedHashMap<String, Object>();
        body.put("id", eventId);
        body.put("summary", event.bookingTypeName());
        body.put("description", description(event));
        body.put("start", eventTime(event.startTime()));
        body.put("end", eventTime(event.endTime()));
        final var json = objectMapper.writeValueAsString(body);

        try {
            authorized(token -> webClient
                    .post()
                    .uri(eventsUri(token)
                            .queryParam("sendUpdates", "none")
                            .build()
                            .encode()
                            .toUri())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(json)
                    .retrieve()
                    .toBodilessEntity()
                    .block());
        } catch (final WebClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                log.info("Google Calendar event {} already exists, reusing deterministic ID", eventId);
                return eventId;
            }
            throw exception;
        }

        log.info("Created Google Calendar event {} for booking {}", eventId, event.confirmationCode());
        return eventId;
    }

    @Retryable(maxAttempts = 3, noRetryFor = IllegalStateException.class)
    public void deleteEvent(final String eventId) {
        try {
            authorized(token -> webClient
                    .delete()
                    .uri(eventUri(token, eventId).build().encode().toUri())
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity()
                    .block());
            log.info("Deleted Google Calendar event {}", eventId);
        } catch (final WebClientResponseException exception) {
            final var status = exception.getStatusCode().value();
            if (status == 404 || status == 410) {
                log.info("Google Calendar event {} already deleted", eventId);
                return;
            }
            throw exception;
        }
    }

    @Retryable(maxAttempts = 3, noRetryFor = IllegalStateException.class)
    public CalendarEventState eventState(final String eventId) {
        try {
            final var event = authorized(token -> getJson(
                    eventUri(token, eventId)
                            .queryParam("fields", "status")
                            .build()
                            .encode()
                            .toUri(),
                    token));
            return "cancelled".equals(event.path("status").asText())
                    ? CalendarEventState.DELETED
                    : CalendarEventState.ACTIVE;
        } catch (final WebClientResponseException exception) {
            return switch (exception.getStatusCode().value()) {
                case 404 -> CalendarEventState.MISSING;
                case 410 -> CalendarEventState.DELETED;
                default -> throw exception;
            };
        }
    }

    /**
     * Google event IDs allow only the characters 0-9 and a-v, so the confirmation code is hex encoded.
     */
    static String eventId(final String confirmationCode) {
        return "booking" + HexFormat.of().formatHex(confirmationCode.getBytes(StandardCharsets.UTF_8));
    }

    private <T> T authorized(final Function<String, T> request) {
        try {
            return request.apply(accessToken());
        } catch (final WebClientResponseException.Unauthorized exception) {
            accessToken = null;
            throw exception;
        }
    }

    private String accessToken() {
        final var current = accessToken;
        if (isFresh(current)) {
            return current.value();
        }
        synchronized (this) {
            if (isFresh(accessToken)) {
                return accessToken.value();
            }
            final var oauth = oauthCredentials();
            final var json = webClient
                    .post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                            .with("client_id", oauth.clientId())
                            .with("client_secret", oauth.clientSecret())
                            .with("refresh_token", oauth.refreshToken()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            final var response = readJson(json);
            final var token = response.path("access_token").asText();
            if (token.isBlank()) {
                throw new IllegalStateException("Google OAuth token response has no access_token");
            }
            final var expiresIn = Duration.ofSeconds(response.path("expires_in").asLong(0));
            accessToken =
                    new GoogleAccessToken(token, clock.instant().plus(expiresIn).minus(TOKEN_EXPIRY_MARGIN));
            return token;
        }
    }

    private boolean isFresh(final GoogleAccessToken token) {
        return Objects.nonNull(token) && clock.instant().isBefore(token.refreshAfter());
    }

    private GoogleCalendarCredentials oauthCredentials() {
        if (Objects.isNull(credentials)) {
            credentials = credentialsProvider.credentials();
        }
        return credentials;
    }

    private String calendarId(final String token) {
        final var existing = calendarId;
        if (Objects.nonNull(existing)) {
            return existing;
        }
        final var name = properties.calendarName();
        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalStateException("calendar.google.calendar-name is required");
        }

        final var matches = new ArrayList<String>();
        String pageToken = null;
        do {
            final var uri = UriComponentsBuilder.fromUriString(API_BASE_URI)
                    .path("/users/me/calendarList")
                    .queryParam("minAccessRole", "writer")
                    .queryParam("showHidden", "true");
            if (Objects.nonNull(pageToken)) {
                uri.queryParam("pageToken", pageToken);
            }
            final var page = getJson(uri.build().encode().toUri(), token);
            for (final var item : page.path("items")) {
                if (name.equals(item.path("summary").asText())) {
                    matches.add(item.path("id").asText());
                }
            }
            pageToken =
                    page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
        } while (Objects.nonNull(pageToken));

        if (matches.isEmpty()) {
            throw new IllegalStateException("No writable Google calendar is named " + name);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("More than one writable Google calendar is named " + name);
        }
        calendarId = matches.getFirst();
        return calendarId;
    }

    private UriComponentsBuilder eventsUri(final String token) {
        return UriComponentsBuilder.fromUriString(API_BASE_URI).pathSegment("calendars", calendarId(token), "events");
    }

    private UriComponentsBuilder eventUri(final String token, final String eventId) {
        return eventsUri(token).pathSegment(eventId);
    }

    private JsonNode getJson(final URI uri, final String token) {
        return readJson(webClient
                .get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(String.class)
                .block());
    }

    private JsonNode readJson(final String json) {
        if (Objects.isNull(json) || json.isBlank()) {
            throw new IllegalStateException("Google API returned an empty response");
        }
        return objectMapper.readTree(json);
    }

    private static Map<String, String> eventTime(final LocalDateTime localDateTime) {
        return Map.of("dateTime", localDateTime.format(LOCAL_DATE_TIME), "timeZone", BOOKING_TIME_ZONE);
    }

    private static String description(final BookingCreatedEvent event) {
        final var description = new StringBuilder();
        description.append("Booking Type: ").append(event.bookingTypeName()).append("\n");
        description
                .append("Confirmation Code: ")
                .append(event.confirmationCode())
                .append("\n");
        description.append("Attendee: ").append(event.attendeeName()).append("\n");
        description.append("Email: ").append(event.attendeeEmail()).append("\n");
        if (Objects.nonNull(event.attendeePhone()) && !event.attendeePhone().isBlank()) {
            description.append("Phone: ").append(event.attendeePhone()).append("\n");
        }
        if (Objects.nonNull(event.message()) && !event.message().isBlank()) {
            description.append("\nMessage:\n").append(event.message());
        }
        return description.toString();
    }
}
