package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GoogleCalendarServiceTest {

    private static final String EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/abc123@group.calendar.google.com/events";
    private static final String CALENDAR_LIST = """
            {"items":[
              {"id":"owner@example.com","summary":"Personal"},
              {"id":"abc123@group.calendar.google.com","summary":"Booking"}
            ]}
            """;

    private final List<ClientRequest> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void derivesGoogleSafeEventIdFromConfirmationCode() {
        final var eventId = GoogleCalendarService.eventId("ABC123");

        assertThat(eventId).isEqualTo("booking414243313233");
        assertThat(eventId).matches("[0-9a-v]{5,1024}");
    }

    @Test
    void insertsEventIntoTheCalendarNamedBooking() {
        final var service = service(CALENDAR_LIST, HttpStatus.OK, "{}");

        final var eventId = service.createEvent(bookingCreatedEvent());

        assertThat(eventId).isEqualTo("booking414243313233");
        assertThat(requests)
                .extracting(ClientRequest::method)
                .containsExactly(HttpMethod.POST, HttpMethod.GET, HttpMethod.POST);
        assertThat(requests.get(1).url().toString())
                .startsWith("https://www.googleapis.com/calendar/v3/users/me/calendarList")
                .contains("minAccessRole=writer");
        final var insert = requests.get(2);
        assertThat(insert.url()).isEqualTo(URI.create(EVENTS_URL + "?sendUpdates=none"));
        assertThat(insert.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer access-1");
        assertThat(bodies.get(2))
                .contains("\"id\":\"booking414243313233\"")
                .contains("\"summary\":\"Consultation\"")
                .contains("\"dateTime\":\"2026-06-12T10:00:00\"")
                .contains("\"dateTime\":\"2026-06-12T11:00:00\"")
                .contains("\"timeZone\":\"America/Chicago\"")
                .contains("Confirmation Code: ABC123");
    }

    @Test
    void reusesTokenAndCalendarIdAcrossCalls() {
        final var service = service(CALENDAR_LIST, HttpStatus.OK, "{}");

        service.createEvent(bookingCreatedEvent());
        service.deleteEvent("booking414243313233");

        assertThat(requests).hasSize(4);
        assertThat(requests.get(3).method()).isEqualTo(HttpMethod.DELETE);
        assertThat(requests.get(3).url()).isEqualTo(URI.create(EVENTS_URL + "/booking414243313233"));
    }

    @Test
    void reusesDeterministicIdWhenEventAlreadyExists() {
        assertThat(service(CALENDAR_LIST, HttpStatus.CONFLICT, "{}").createEvent(bookingCreatedEvent()))
                .isEqualTo("booking414243313233");
    }

    @Test
    void treatsMissingOrGoneEventAsAlreadyDeleted() {
        service(CALENDAR_LIST, HttpStatus.NOT_FOUND, "{}").deleteEvent("booking41");
        service(CALENDAR_LIST, HttpStatus.GONE, "{}").deleteEvent("booking41");
    }

    @Test
    void reportsEventStateFromGoogle() {
        assertThat(service(CALENDAR_LIST, HttpStatus.OK, "{\"status\":\"confirmed\"}")
                        .eventState("booking41"))
                .isEqualTo(CalendarEventState.ACTIVE);
        assertThat(service(CALENDAR_LIST, HttpStatus.OK, "{\"status\":\"cancelled\"}")
                        .eventState("booking41"))
                .isEqualTo(CalendarEventState.DELETED);
        assertThat(service(CALENDAR_LIST, HttpStatus.GONE, "{}").eventState("booking41"))
                .isEqualTo(CalendarEventState.DELETED);
        assertThat(service(CALENDAR_LIST, HttpStatus.NOT_FOUND, "{}").eventState("booking41"))
                .isEqualTo(CalendarEventState.MISSING);
    }

    @Test
    void failsWhenNoCalendarIsNamedBooking() {
        final var service = service("{\"items\":[{\"id\":\"x\",\"summary\":\"Personal\"}]}", HttpStatus.OK, "{}");

        assertThatThrownBy(service::verifyCalendar)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No writable Google calendar is named Booking");
    }

    @Test
    void failsWhenTwoCalendarsAreNamedBooking() {
        final var service = service("""
                {"items":[{"id":"a","summary":"Booking"},{"id":"b","summary":"Booking"}]}
                """, HttpStatus.OK, "{}");

        assertThatThrownBy(service::verifyCalendar)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than one");
    }

    @Test
    void doesNotCopyGoogleCredentialsOntoTheSharedBuilder() {
        final var builder = builder(CALENDAR_LIST, HttpStatus.OK, "{}");
        new GoogleCalendarService(properties(), credentialsProvider(), builder, clock)
                .createEvent(bookingCreatedEvent());
        final var followUp = new ArrayList<ClientRequest>();

        builder.exchangeFunction(request -> {
                    followUp.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build()
                .get()
                .uri("https://example.test/health")
                .retrieve()
                .toBodilessEntity()
                .block();

        assertThat(followUp.getFirst().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isNull();
    }

    private static CalendarProperties properties() {
        return new CalendarProperties(true, "Booking", "google/booking-calendar-oauth", "us-east-1");
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

    private GoogleCalendarService service(
            final String calendarList, final HttpStatus eventStatus, final String eventBody) {
        return new GoogleCalendarService(
                properties(), credentialsProvider(), builder(calendarList, eventStatus, eventBody), clock);
    }

    private static GoogleCalendarCredentialsProvider credentialsProvider() {
        final var provider = Mockito.mock(GoogleCalendarCredentialsProvider.class);
        when(provider.credentials())
                .thenReturn(new GoogleCalendarCredentials("client-id", "client-secret", "refresh-token"));
        return provider;
    }

    private WebClient.Builder builder(final String calendarList, final HttpStatus eventStatus, final String eventBody) {
        return WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            bodies.add(body(request));
            final var url = request.url().toString();
            if (url.equals(GoogleCalendarService.TOKEN_URI)) {
                return json(HttpStatus.OK, "{\"access_token\":\"access-1\",\"expires_in\":3600}");
            }
            if (url.contains("/users/me/calendarList")) {
                return json(HttpStatus.OK, calendarList);
            }
            assertThat(url).startsWith(EVENTS_URL);
            return json(eventStatus, eventBody);
        });
    }

    private static String body(final ClientRequest request) {
        if (request.method() != HttpMethod.POST) {
            return "";
        }
        final var captured = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(captured, ExchangeStrategies.withDefaults()).block();
        return Objects.requireNonNullElse(captured.getBodyAsString().block(), "");
    }

    private static Mono<ClientResponse> json(final HttpStatus status, final String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build());
    }
}
