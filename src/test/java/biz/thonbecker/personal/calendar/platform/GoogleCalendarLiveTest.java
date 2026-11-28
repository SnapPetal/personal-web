package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@EnabledIfEnvironmentVariable(named = "GOOGLE_CALENDAR_LIVE_TEST", matches = "true")
class GoogleCalendarLiveTest {
    @Test
    void createsAndRemovesAnIsolatedTestEvent() {
        try (final var secrets =
                SecretsManagerClient.builder().region(Region.US_EAST_1).build()) {
            final var google = new GoogleCalendarClient(
                    secrets,
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(15))
                            .build());
            final var session = google.session();
            final var id = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, Long.MAX_VALUE);
            final var start = LocalDateTime.of(2026, 1, 1, 10, 0);
            final var booking = new BookingCreatedEvent(
                    id,
                    "TEST",
                    "test@example.invalid",
                    "Test",
                    null,
                    "Temporary PersonalWeb connection test",
                    start,
                    start.plusMinutes(15),
                    null,
                    null);
            try {
                google.create(session, booking);
                assertThat(google.exists(session, id)).isTrue();
                google.create(session, booking);
                assertThat(google.exists(session, id)).isTrue();
            } finally {
                google.delete(session, id);
            }
            assertThat(google.exists(session, id)).isFalse();
        }
    }
}
