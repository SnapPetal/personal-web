package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import biz.thonbecker.personal.TestcontainersConfig;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ApplicationModuleTest(extraIncludes = {"booking", "shared", "analytics"})
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "calendar.google.enabled=true")
class GoogleBookingIntegrationTest {
    @Autowired
    private BookingService bookingService;

    @Autowired
    private CalendarEventMappingRepository mappings;

    @Autowired
    private GoogleBookingScheduler scheduler;

    @MockitoBean
    private GoogleCalendarClient google;

    private final GoogleCalendarClient.Session session = new GoogleCalendarClient.Session("calendar", "token");

    @BeforeEach
    void setup() {
        when(google.session()).thenReturn(session);
        when(google.marker(session)).thenReturn("google:test");
        when(google.eventId(any())).thenAnswer(invocation -> "b" + invocation.getArgument(0));
    }

    @Test
    void createsAndCancelsCalendarEventsForRealBookingTransactions() {
        final var type = bookingService.createBookingType("Calendar integration", "test", 30, 0, "#000000");
        final var day = LocalDate.now().plusDays(15);
        bookingService.createAvailabilitySlot(day.atTime(9, 0), day.atTime(17, 0));
        final var booking = bookingService.createBooking(
                type.id(), "Test Person", "test@example.com", null, day.atTime(10, 0), null, null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(mappings.findByBookingId(booking.id()))
                    .hasValueSatisfying(
                            mapping -> assertThat(mapping.getCalendarHref()).isEqualTo("google:test"));
            verify(google)
                    .create(eq(session), argThat(event -> event.bookingId().equals(booking.id())));
        });
        bookingService.cancelBooking(booking.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(mappings.findByBookingId(booking.id())).isEmpty();
            verify(google, atLeastOnce()).delete(session, booking.id());
        });
        scheduler.sync();
        assertThat(bookingService
                        .getBookingByConfirmationCode(booking.confirmationCode())
                        .status()
                        .name())
                .isEqualTo("CANCELLED");
    }
}
