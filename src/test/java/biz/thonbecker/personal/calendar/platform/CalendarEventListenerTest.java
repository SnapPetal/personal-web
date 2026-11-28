package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarProvider;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class CalendarEventListenerTest {

    @Test
    void persistsGoogleMappingForCreatedEvent() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, true);

        final var event = bookingCreatedEvent();

        when(calendarService.createEvent(event)).thenReturn("booking414243313233");
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.empty());

        listener.onBookingCreated(event);

        verify(calendarService).createEvent(event);
        verify(mappingRepository)
                .save(Mockito.argThat(mapping -> mapping.getBookingId().equals(event.bookingId())
                        && mapping.getCalendarUid().equals("booking414243313233")
                        && mapping.getCalendarProvider() == CalendarProvider.GOOGLE));
    }

    @Test
    void deletesDeterministicUidWhenMappingIsMissing() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, true);

        final var event = bookingCancelledEvent();
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.empty());

        listener.onBookingCancelled(event);

        verify(calendarService).deleteEvent("booking414243313233");
        verify(mappingRepository, never()).delete(Mockito.any());
    }

    @Test
    void deletesGoogleEventAndKeepsNoLegacyMappingWhenBookingWasOnOpenCloud() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, true);

        final var event = bookingCancelledEvent();
        final var mapping = mapping(event.bookingId(), "booking-ABC123", CalendarProvider.OPENCLOUD);
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.of(mapping));

        listener.onBookingCancelled(event);

        verify(calendarService).deleteEvent("booking414243313233");
        verify(mappingRepository).delete(mapping);
    }

    @Test
    void replayedCreateMovesLegacyMappingToGoogle() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, true);

        final var event = bookingCreatedEvent();
        final var mapping = mapping(event.bookingId(), "booking-ABC123", CalendarProvider.OPENCLOUD);
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.of(mapping));
        when(calendarService.createEvent(event)).thenReturn("booking414243313233");

        listener.onBookingCreated(event);

        verify(mappingRepository).save(mapping);
        assertThat(mapping.getCalendarUid()).isEqualTo("booking414243313233");
        assertThat(mapping.getCalendarProvider()).isEqualTo(CalendarProvider.GOOGLE);
    }

    @Test
    void leavesMappingInPlaceWhenCalendarDeletionFails() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, true);

        final var event = bookingCancelledEvent();
        final var mapping = mapping(event.bookingId(), "booking414243313233", CalendarProvider.GOOGLE);

        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.of(mapping));
        Mockito.doThrow(new IllegalStateException("delete failed"))
                .when(calendarService)
                .deleteEvent("booking414243313233");

        assertThrows(IllegalStateException.class, () -> listener.onBookingCancelled(event));

        verify(mappingRepository, never()).delete(mapping);
    }

    @Test
    void skipsCreationWhenGoogleCalendarIsDisabled() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener = listener(calendarService, mappingRepository, false);

        listener.onBookingCreated(bookingCreatedEvent());

        verify(calendarService, never()).createEvent(Mockito.any());
        verify(mappingRepository, never()).save(Mockito.any());
    }

    @Test
    void reportsCalendarCreateFailuresAndRethrows() {
        final var calendarService = Mockito.mock(GoogleCalendarService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        final var listener = listener(calendarService, mappingRepository, true, eventPublisher);
        final var event = bookingCreatedEvent();
        when(calendarService.createEvent(event))
                .thenThrow(new IllegalStateException("Google rejected attendee@example.com"));

        assertThrows(IllegalStateException.class, () -> listener.onBookingCreated(event));

        verify(mappingRepository, never()).save(Mockito.any());
        verify(eventPublisher)
                .publishEvent(
                        Mockito.<Object>argThat(published -> published instanceof ServerExceptionReportedEvent reported
                                && "calendar.booking-created".equals(reported.source())
                                && reported.message().contains("[redacted-email]")
                                && !reported.message().contains("attendee@example.com")));
    }

    private static CalendarEventListener listener(
            final GoogleCalendarService calendarService,
            final CalendarEventMappingRepository mappingRepository,
            final boolean enabled) {
        return listener(calendarService, mappingRepository, enabled, Mockito.mock(ApplicationEventPublisher.class));
    }

    private static CalendarEventListener listener(
            final GoogleCalendarService calendarService,
            final CalendarEventMappingRepository mappingRepository,
            final boolean enabled,
            final ApplicationEventPublisher eventPublisher) {
        return new CalendarEventListener(
                objectProvider(calendarService), mappingRepository, calendarProperties(enabled), eventPublisher);
    }

    private static ObjectProvider<GoogleCalendarService> objectProvider(final GoogleCalendarService service) {
        return new ObjectProvider<>() {
            @Override
            public GoogleCalendarService getObject(final Object... args) {
                return service;
            }

            @Override
            public GoogleCalendarService getIfAvailable() {
                return service;
            }

            @Override
            public GoogleCalendarService getIfUnique() {
                return service;
            }

            @Override
            public GoogleCalendarService getObject() {
                return service;
            }
        };
    }

    private static CalendarProperties calendarProperties(final boolean enabled) {
        return new CalendarProperties(enabled, "Booking", "google/booking-calendar-oauth", "us-east-1");
    }

    private static CalendarEventMappingEntity mapping(
            final long bookingId, final String calendarUid, final CalendarProvider provider) {
        final var mapping = new CalendarEventMappingEntity();
        mapping.setBookingId(bookingId);
        mapping.setCalendarUid(calendarUid);
        mapping.setCalendarProvider(provider);
        return mapping;
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

    private static BookingCancelledEvent bookingCancelledEvent() {
        final var start = LocalDateTime.of(2026, 6, 12, 10, 0);
        return new BookingCancelledEvent(
                42L, "ABC123", "attendee@example.com", "Attendee", "Consultation", start, start.plusHours(1), null);
    }
}
