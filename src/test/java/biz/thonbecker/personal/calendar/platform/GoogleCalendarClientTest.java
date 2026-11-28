package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

class GoogleCalendarClientTest {
    private final SecretsManagerClient secrets = mock(SecretsManagerClient.class);
    private final HttpClient http = mock(HttpClient.class);
    private final GoogleCalendarClient google = new GoogleCalendarClient(secrets, http);
    private final GoogleCalendarClient.Session session =
            new GoogleCalendarClient.Session("booking@group.calendar.google.com", "access");

    @BeforeEach
    void setup() {
        when(secrets.getSecretValue(any(GetSecretValueRequest.class))).thenAnswer(invocation -> {
            final GetSecretValueRequest request = invocation.getArgument(0);
            final var json = request.secretId().endsWith("oauth-client")
                    ? "{\"installed\":{\"client_id\":\"client\",\"client_secret\":\"secret\"}}"
                    : "{\"calendar_id\":\"booking@group.calendar.google.com\",\"refresh_token\":\"refresh\"}";
            return GetSecretValueResponse.builder().secretString(json).build();
        });
    }

    @Test
    void verifiesCalendarAccessAfterRefreshing() throws Exception {
        final var tokenResponse = response(200, "{\"access_token\":\"access\"}");
        final var calendarResponse = response(200, "{\"accessRole\":\"owner\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse)
                .thenReturn(calendarResponse);
        assertThat(google.session()).isEqualTo(session);
        final var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().get(0).uri().toString()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(requests.getAllValues().get(1).uri().toString())
                .isEqualTo("https://www.googleapis.com/calendar/v3/calendars/booking%40group.calendar.google.com"
                        + "/events?maxResults=1&fields=accessRole");
        assertThat(requests.getAllValues().get(1).headers().firstValue("Authorization"))
                .contains("Bearer access");
    }

    @Test
    void readOnlyCalendarFails() throws Exception {
        final var tokenResponse = response(200, "{\"access_token\":\"access\"}");
        final var calendarResponse = response(200, "{\"accessRole\":\"reader\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse)
                .thenReturn(calendarResponse);
        assertThatThrownBy(google::session).hasMessageContaining("not writable");
    }

    @Test
    void forbiddenCalendarReportsGoogleReason() throws Exception {
        final var tokenResponse = response(200, "{\"access_token\":\"access\"}");
        final var calendarResponse = response(
                403,
                "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientPermissions\"}],"
                        + "\"status\":\"PERMISSION_DENIED\"}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse)
                .thenReturn(calendarResponse);
        assertThatThrownBy(google::session)
                .hasMessage("Google Calendar could not verify calendar (HTTP 403, reason insufficientPermissions)");
    }

    @Test
    void rejectedRefreshReportsOAuthError() throws Exception {
        final var tokenResponse = response(400, "{\"error\":\"invalid_grant\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse);
        assertThatThrownBy(google::session)
                .hasMessage("Google Calendar could not refresh authorization (HTTP 400, reason invalid_grant)");
    }

    @Test
    void inaccessibleCalendarFailsInsteadOfTreatingEventsAsDeleted() throws Exception {
        final var tokenResponse = response(200, "{\"access_token\":\"access\"}");
        final var calendarResponse = response(404, "{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse)
                .thenReturn(calendarResponse);
        assertThatThrownBy(google::session).hasMessageContaining("verify calendar");
    }

    @Test
    void absenceIsOnlyRecognizedForMissingOrCancelledEvents() throws Exception {
        final var missing = response(404, "{}");
        final var cancelled = response(200, "{\"status\":\"cancelled\"}");
        final var forbidden = response(403, "{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(missing)
                .thenReturn(cancelled)
                .thenReturn(forbidden);
        assertThat(google.exists(session, 42L)).isFalse();
        assertThat(google.exists(session, 42L)).isFalse();
        assertThatThrownBy(() -> google.exists(session, 42L)).hasMessageContaining("HTTP 403");
    }

    @Test
    void refusesToReuseOtherPeoplesEvents() throws Exception {
        final var conflict = response(409, "{}");
        final var other = response(
                200,
                "{\"status\":\"confirmed\",\"extendedProperties\":{\"private\":{\"personalWebBookingId\":\"99\"}}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(conflict)
                .thenReturn(other);
        final var start = LocalDateTime.of(2026, 12, 1, 10, 0);
        final var booking = new BookingCreatedEvent(
                42L, "ABC", "a@example.com", "A", null, "Meeting", start, start.plusHours(1), null, null);
        assertThatThrownBy(() -> google.create(session, booking)).hasMessageContaining("does not belong");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(final int status, final String body) {
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
