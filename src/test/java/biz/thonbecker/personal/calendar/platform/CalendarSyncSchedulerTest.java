package biz.thonbecker.personal.calendar.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarProvider;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class CalendarSyncSchedulerTest {

    private final GoogleCalendarService calendarService = Mockito.mock(GoogleCalendarService.class);
    private final CalendarEventMappingRepository repository = Mockito.mock(CalendarEventMappingRepository.class);
    private final ApplicationEventPublisher publisher = Mockito.mock(ApplicationEventPublisher.class);
    private final CalendarSyncScheduler scheduler = new CalendarSyncScheduler(calendarService, repository, publisher);

    @Test
    void requestsCancellationOnlyWhenGoogleReportsTheEventDeleted() {
        when(repository.findByCalendarProvider(CalendarProvider.GOOGLE))
                .thenReturn(
                        List.of(mapping(7L, "bookinggone"), mapping(8L, "bookingkeep"), mapping(9L, "bookingnone")));
        when(calendarService.eventState("bookinggone")).thenReturn(CalendarEventState.DELETED);
        when(calendarService.eventState("bookingkeep")).thenReturn(CalendarEventState.ACTIVE);
        when(calendarService.eventState("bookingnone")).thenReturn(CalendarEventState.MISSING);

        scheduler.syncCalendarDeletions();

        verify(publisher).publishEvent(new BookingCancellationRequestedEvent(7L));
        verify(publisher, never()).publishEvent(new BookingCancellationRequestedEvent(8L));
        verify(publisher, never()).publishEvent(new BookingCancellationRequestedEvent(9L));
    }

    @Test
    void neverReadsOpenCloudMappings() {
        when(repository.findByCalendarProvider(CalendarProvider.GOOGLE)).thenReturn(List.of());

        scheduler.syncCalendarDeletions();

        verify(repository, never()).findAll();
        verify(calendarService, never()).eventState(any());
        verify(publisher, never()).publishEvent(any(BookingCancellationRequestedEvent.class));
    }

    @Test
    void skipsTheWholeRunAndReportsWhenAnyReadFails() {
        when(repository.findByCalendarProvider(CalendarProvider.GOOGLE))
                .thenReturn(List.of(mapping(7L, "bookinggone"), mapping(8L, "bookingfail")));
        when(calendarService.eventState("bookinggone")).thenReturn(CalendarEventState.DELETED);
        when(calendarService.eventState("bookingfail")).thenThrow(new IllegalStateException("Google is down"));

        scheduler.syncCalendarDeletions();

        verify(publisher, never()).publishEvent(any(BookingCancellationRequestedEvent.class));
        verify(publisher)
                .publishEvent(Mockito.<Object>argThat(published ->
                        published instanceof ServerExceptionReportedEvent r && "calendar.sync".equals(r.source())));
    }

    @Test
    void reportsStartupVerificationFailure() {
        Mockito.doThrow(new IllegalStateException("No writable Google calendar is named Booking"))
                .when(calendarService)
                .verifyCalendar();

        scheduler.init();

        verify(publisher)
                .publishEvent(Mockito.<Object>argThat(published ->
                        published instanceof ServerExceptionReportedEvent r && "calendar.verify".equals(r.source())));
    }

    private static CalendarEventMappingEntity mapping(final long bookingId, final String calendarUid) {
        final var mapping = new CalendarEventMappingEntity();
        mapping.setBookingId(bookingId);
        mapping.setCalendarUid(calendarUid);
        mapping.setCalendarProvider(CalendarProvider.GOOGLE);
        return mapping;
    }
}
