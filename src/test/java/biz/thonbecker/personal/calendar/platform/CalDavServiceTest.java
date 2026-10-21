package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class CalDavServiceTest {

    private static final String BASE_URL = "https://files.thonbecker.biz/caldav/";
    private static final String CALENDAR_URL =
            "https://files.thonbecker.biz/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/";

    @Test
    void putsEventsOnTheConfiguredCollection() {
        final var requests = new ArrayList<ClientRequest>();
        final var service = service(requests, HttpStatus.CREATED, null);

        final var uid = service.createEvent(bookingCreatedEvent());

        assertThat(uid).isEqualTo("booking-ABC123");
        assertThat(requests).hasSize(1);
        final var request = requests.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.url()).isEqualTo(URI.create(CALENDAR_URL + "booking-ABC123.ics"));
        assertThat(request.url().toString()).doesNotContain("remote.php");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(basicAuth());
        assertThat(request.headers().getFirst(HttpHeaders.IF_NONE_MATCH)).isEqualTo("*");
        assertThat(request.headers().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/calendar");
        assertThat(Integer.parseInt(request.headers().getFirst(HttpHeaders.CONTENT_LENGTH)))
                .isPositive();
    }

    @Test
    void doesNotCopyCalendarCredentialsOntoTheSharedBuilder() {
        final var requests = new ArrayList<ClientRequest>();
        final var followUp = new ArrayList<ClientRequest>();
        final ExchangeFunction exchange = Mockito.mock(ExchangeFunction.class);
        when(exchange.exchange(any())).thenAnswer(invocation -> {
            final ClientRequest request = invocation.getArgument(0);
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.CREATED).build());
        });
        final var tokenProvider = Mockito.mock(CalDavAppTokenProvider.class);
        when(tokenProvider.bookingAppToken()).thenReturn("app-token");
        final var builder = WebClient.builder().exchangeFunction(exchange);
        final var service = new CalDavService(new IcsGenerator(), properties(), builder, tokenProvider);

        service.createEvent(bookingCreatedEvent());

        builder.exchangeFunction(request -> {
                    followUp.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build()
                .get()
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .block();

        assertThat(followUp).hasSize(1);
        assertThat(followUp.getFirst().url().toString()).doesNotContain("caldav");
        assertThat(followUp.getFirst().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isNull();
        assertThat(requests).hasSize(1);
    }

    @Test
    void reusesDeterministicUidWhenEventAlreadyExists() {
        final var requests = new ArrayList<ClientRequest>();
        final var service = service(requests, HttpStatus.PRECONDITION_FAILED, null);

        assertThat(service.createEvent(bookingCreatedEvent())).isEqualTo("booking-ABC123");
        assertThat(requests.getFirst().url()).isEqualTo(URI.create(CALENDAR_URL + "booking-ABC123.ics"));
    }

    @Test
    void treatsMissingEventAsAlreadyDeleted() {
        final var requests = new ArrayList<ClientRequest>();
        final var service = service(requests, HttpStatus.NOT_FOUND, null);

        service.deleteEvent("booking-ABC123");

        assertThat(requests.getFirst().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(requests.getFirst().url()).isEqualTo(URI.create(CALENDAR_URL + "booking-ABC123.ics"));
    }

    @Test
    void listsEventUidsFromCollectionPropfind() {
        final var requests = new ArrayList<ClientRequest>();
        final var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/</d:href>
                  </d:response>
                  <d:response>
                    <d:href>/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/booking-ABC123.ics</d:href>
                  </d:response>
                  <d:response>
                    <d:href>https://files.thonbecker.biz/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/booking-KEEP.ics</d:href>
                  </d:response>
                </d:multistatus>
                """;
        final var service = service(requests, HttpStatus.MULTI_STATUS, xml);

        assertThat(service.listEventResources()).containsExactly("booking-ABC123", "booking-KEEP");
        assertThat(requests.getFirst().method().name()).isEqualTo("PROPFIND");
        assertThat(requests.getFirst().url()).isEqualTo(URI.create(CALENDAR_URL));
        assertThat(requests.getFirst().headers().getFirst("Depth")).isEqualTo("1");
        assertThat(requests).noneMatch(request -> request.method().name().equals("MKCALENDAR"));
    }

    @Test
    void rejectsUnparseablePropfindInsteadOfTreatingEventsAsDeleted() {
        final var service = service(new ArrayList<>(), HttpStatus.MULTI_STATUS, "this is not xml");

        assertThatThrownBy(service::listEventResources)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROPFIND");
    }

    @Test
    void verifiesTheCollectionWithoutCreatingIt() {
        final var requests = new ArrayList<ClientRequest>();
        final var service = service(requests, HttpStatus.MULTI_STATUS, null);

        service.verifyCalendarCollection();

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method().name()).isEqualTo("PROPFIND");
        assertThat(requests.getFirst().url()).isEqualTo(URI.create(CALENDAR_URL));
        assertThat(requests.getFirst().headers().getFirst("Depth")).isEqualTo("0");
    }

    @Test
    void normalizesCollectionUrlBeforeAppendingEvent() {
        final var requests = new ArrayList<ClientRequest>();
        final var properties = new CalendarProperties(
                true,
                "https://files.thonbecker.biz/caldav",
                "https://files.thonbecker.biz/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings",
                "thonbecker",
                "opencloud/calendar-app-tokens",
                "booking_app_token",
                "us-east-1",
                "org@example.com");
        final var service = service(requests, HttpStatus.CREATED, null, properties);

        service.createEvent(bookingCreatedEvent());

        assertThat(requests.getFirst().url()).isEqualTo(URI.create(CALENDAR_URL + "booking-ABC123.ics"));
    }

    @Test
    void rejectsCalendarUrlOutsideTheBaseUrl() {
        final var properties = new CalendarProperties(
                true,
                "https://files.thonbecker.biz/caldav/",
                "https://cloud.thonbecker.biz/remote.php/dav/calendars/thonbecker/bookings/",
                "thonbecker",
                "opencloud/calendar-app-tokens",
                "booking_app_token",
                "us-east-1",
                "org@example.com");

        assertThatThrownBy(() -> service(new ArrayList<>(), HttpStatus.OK, null, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calendar.caldav.calendar-url");
    }

    private static CalDavService service(
            final List<ClientRequest> requests, final HttpStatus status, final String body) {
        return service(requests, status, body, properties());
    }

    private static CalDavService service(
            final List<ClientRequest> requests,
            final HttpStatus status,
            final String body,
            final CalendarProperties properties) {
        final ExchangeFunction exchange = Mockito.mock(ExchangeFunction.class);
        when(exchange.exchange(any())).thenAnswer(invocation -> {
            final ClientRequest request = invocation.getArgument(0);
            requests.add(request);
            final var response = ClientResponse.create(status);
            if (body != null) {
                response.header(HttpHeaders.CONTENT_TYPE, "application/xml").body(body);
            }
            return Mono.just(response.build());
        });
        final var tokenProvider = Mockito.mock(CalDavAppTokenProvider.class);
        when(tokenProvider.bookingAppToken()).thenReturn("app-token");
        return new CalDavService(
                new IcsGenerator(), properties, WebClient.builder().exchangeFunction(exchange), tokenProvider);
    }

    private static CalendarProperties properties() {
        return new CalendarProperties(
                true,
                BASE_URL,
                CALENDAR_URL,
                "thonbecker",
                "opencloud/calendar-app-tokens",
                "booking_app_token",
                "us-east-1",
                "org@example.com");
    }

    private static String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("thonbecker:app-token".getBytes(StandardCharsets.UTF_8));
    }

    private static BookingCreatedEvent bookingCreatedEvent() {
        final var start = LocalDateTime.of(2026, 6, 12, 10, 0);
        return new BookingCreatedEvent(
                42L,
                "ABC123",
                "attendee@example.com",
                "Attendee",
                "555-1212",
                "Consultation",
                start,
                start.plusHours(1),
                "hello",
                null);
    }
}
