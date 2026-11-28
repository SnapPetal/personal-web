package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import biz.thonbecker.personal.booking.api.BookingCalendarAccess;
import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class GoogleBookingSynchronizerTest {
    private final BookingCalendarAccess bookings = mock(BookingCalendarAccess.class);
    private final CalendarEventMappingRepository mappings = mock(CalendarEventMappingRepository.class);
    private final GoogleCalendarClient google = mock(GoogleCalendarClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final GoogleBookingSynchronizer synchronizer =
            new GoogleBookingSynchronizer(bookings, mappings, google, publisher);
    private final GoogleCalendarClient.Session session = new GoogleCalendarClient.Session("calendar", "token");
    private final BookingCreatedEvent booking = new BookingCreatedEvent(
            42L,
            "ABC",
            "a@example.com",
            "A",
            null,
            "Meeting",
            LocalDateTime.of(2026, 12, 1, 10, 0),
            LocalDateTime.of(2026, 12, 1, 11, 0),
            null,
            null);

    @BeforeEach
    void setup() {
        when(bookings.lockActiveBooking(42L)).thenReturn(Optional.of(booking));
        when(google.session()).thenReturn(session);
        when(google.marker(session)).thenReturn("google:correct");
        when(google.eventId(42L)).thenReturn("b123");
    }

    @Test
    void migratesLegacyMappingWithoutCancellingBooking() {
        final var legacy = mapping("booking-ABC.ics");
        when(mappings.findByBookingId(42L)).thenReturn(Optional.of(legacy));
        synchronizer.synchronize(42L, true);
        verify(google).create(session, booking);
        verify(mappings)
                .save(argThat(mapping ->
                        "google:correct".equals(mapping.getCalendarHref()) && "b123".equals(mapping.getCalendarUid())));
        verify(publisher, never()).publishEvent(any(Object.class));
        verify(google, never()).exists(any(), any());
    }

    @Test
    void failedCreationPreservesLegacyMapping() {
        final var legacy = mapping("booking-ABC.ics");
        when(mappings.findByBookingId(42L)).thenReturn(Optional.of(legacy));
        doThrow(new IllegalStateException("failure")).when(google).create(session, booking);
        assertThatThrownBy(() -> synchronizer.synchronize(42L, true)).isInstanceOf(IllegalStateException.class);
        verify(mappings, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void onlyVerifiedGoogleMappingCanRequestCancellation() {
        when(mappings.findByBookingId(42L)).thenReturn(Optional.of(mapping("google:correct")));
        when(google.exists(session, 42L)).thenReturn(false);
        synchronizer.synchronize(42L, true);
        verify(publisher).publishEvent(new BookingCancellationRequestedEvent(42L));
        verify(google, never()).create(any(), any());
    }

    @Test
    void rejectsChangingCalendarWithoutReconciliation() {
        when(mappings.findByBookingId(42L)).thenReturn(Optional.of(mapping("google:other")));
        assertThatThrownBy(() -> synchronizer.synchronize(42L, true)).hasMessageContaining("calendar changed");
        verify(publisher, never()).publishEvent(any(Object.class));
        verify(google, never()).create(any(), any());
    }

    @Test
    void staleCreationForCancelledBookingDoesNothing() {
        when(bookings.lockActiveBooking(42L)).thenReturn(Optional.empty());
        synchronizer.synchronize(42L, false);
        verify(google, never()).session();
    }

    @Test
    void cancellationDeletesOnlyAfterBookingIsCancelled() {
        synchronizer.cancel(42L);
        verify(google, never()).delete(any(), any());
        when(bookings.lockActiveBooking(42L)).thenReturn(Optional.empty());
        when(mappings.findByBookingId(42L)).thenReturn(Optional.of(mapping("google:correct")));
        synchronizer.cancel(42L);
        verify(google).delete(session, 42L);
        verify(mappings).delete(any());
    }

    private static CalendarEventMappingEntity mapping(final String href) {
        final var mapping = new CalendarEventMappingEntity();
        mapping.setBookingId(42L);
        mapping.setCalendarUid("booking-ABC");
        mapping.setCalendarHref(href);
        return mapping;
    }
}
