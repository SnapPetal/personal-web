package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;

import biz.thonbecker.personal.IntegrationTest;
import biz.thonbecker.personal.booking.api.BookingCalendarAccess;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
@TestPropertySource(properties = "calendar.google.enabled=true")
class GoogleBookingPublicationTest {
    private final JdbcTemplate jdbc;

    @MockitoBean
    private BookingCalendarAccess bookings;

    @MockitoBean
    private GoogleCalendarClient google;

    @MockitoBean
    private GoogleBookingSynchronizer synchronizer;

    GoogleBookingPublicationTest(final DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void simulateFailure() {
        doThrow(new IllegalStateException("Google unavailable"))
                .when(synchronizer)
                .synchronize(any(), anyBoolean());
    }

    @Test
    void failedGoogleListenerRemainsOutstanding(final Scenario scenario) {
        final var before = outstanding();
        final var start = LocalDateTime.now().plusDays(1);
        final var event = new BookingCreatedEvent(
                999L, "TEST", "test@example.com", "Test", null, "Meeting", start, start.plusMinutes(30), null, null);
        scenario.publish(event).andWaitForStateChange(() -> outstanding() > before);
        assertThat(outstanding()).isGreaterThan(before);
    }

    private int outstanding() {
        return jdbc.queryForObject("""
                select count(*) from event_publication
                where completion_date is null and listener_id like '%GoogleBookingListener%'
                """, Integer.class);
    }
}
