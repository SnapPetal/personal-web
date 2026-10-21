package biz.thonbecker.personal.calendar.platform;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class CalendarSyncSchedulerTest {

    @Test
    void requestsCancellationWhenEventIsMissingFromCollection() {
        final var calDavService = Mockito.mock(CalDavService.class);
        final var repository = Mockito.mock(CalendarEventMappingRepository.class);
        final var publisher = Mockito.mock(ApplicationEventPublisher.class);
        final var scheduler = new CalendarSyncScheduler(calDavService, repository, publisher);

        final var gone = mapping(7L, "booking-GONE");
        final var kept = mapping(8L, "booking-KEEP");
        when(calDavService.listEventResources()).thenReturn(List.of("booking-KEEP"));
        when(repository.findAll()).thenReturn(List.of(gone, kept));

        scheduler.syncCalendarDeletions();

        verify(publisher).publishEvent(new BookingCancellationRequestedEvent(7L));
        verify(publisher, never()).publishEvent(new BookingCancellationRequestedEvent(8L));
    }

    private static CalendarEventMappingEntity mapping(final long bookingId, final String calendarUid) {
        final var mapping = new CalendarEventMappingEntity();
        mapping.setBookingId(bookingId);
        mapping.setCalendarUid(calendarUid);
        return mapping;
    }
}
