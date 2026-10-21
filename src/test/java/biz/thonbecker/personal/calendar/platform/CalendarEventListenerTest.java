package biz.thonbecker.personal.calendar.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class CalendarEventListenerTest {

    @Test
    void persistsMappingWhenCalDavEventAlreadyExists() {
        final var calDavService = Mockito.mock(CalDavService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener =
                new CalendarEventListener(objectProvider(calDavService), mappingRepository, calendarProperties(true));

        final var event = bookingCreatedEvent();

        when(calDavService.createEvent(event)).thenReturn("booking-ABC123");
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.empty());

        listener.onBookingCreated(event);

        verify(calDavService).createEvent(event);
        verify(mappingRepository)
                .save(Mockito.argThat(mapping -> mapping.getBookingId().equals(event.bookingId())
                        && mapping.getCalendarUid().equals("booking-ABC123")
                        && mapping.getCalendarHref().equals("booking-ABC123.ics")));
    }

    @Test
    void deletesDeterministicUidWhenMappingIsMissing() {
        final var calDavService = Mockito.mock(CalDavService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener =
                new CalendarEventListener(objectProvider(calDavService), mappingRepository, calendarProperties(true));

        final var event = bookingCancelledEvent();
        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.empty());

        listener.onBookingCancelled(event);

        verify(calDavService).deleteEvent("booking-" + event.confirmationCode());
        verify(mappingRepository, never()).delete(Mockito.any());
    }

    @Test
    void leavesMappingInPlaceWhenCalendarDeletionFails() {
        final var calDavService = Mockito.mock(CalDavService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener =
                new CalendarEventListener(objectProvider(calDavService), mappingRepository, calendarProperties(true));

        final var event = bookingCancelledEvent();
        final var mapping = new CalendarEventMappingEntity();
        mapping.setBookingId(event.bookingId());
        mapping.setCalendarUid("booking-" + event.confirmationCode());

        when(mappingRepository.findByBookingId(event.bookingId())).thenReturn(Optional.of(mapping));
        Mockito.doThrow(new IllegalStateException("delete failed"))
                .when(calDavService)
                .deleteEvent("booking-" + event.confirmationCode());

        assertThrows(IllegalStateException.class, () -> listener.onBookingCancelled(event));

        verify(mappingRepository, never()).delete(mapping);
    }

    @Test
    void skipsCreationWhenCalDavIsDisabled() {
        final var calDavService = Mockito.mock(CalDavService.class);
        final var mappingRepository = Mockito.mock(CalendarEventMappingRepository.class);
        final var listener =
                new CalendarEventListener(objectProvider(calDavService), mappingRepository, calendarProperties(false));

        listener.onBookingCreated(bookingCreatedEvent());

        verify(calDavService, never()).createEvent(Mockito.any());
        verify(mappingRepository, never()).save(Mockito.any());
    }

    private static ObjectProvider<CalDavService> objectProvider(final CalDavService service) {
        return new ObjectProvider<>() {
            @Override
            public CalDavService getObject(final Object... args) {
                return service;
            }

            @Override
            public CalDavService getIfAvailable() {
                return service;
            }

            @Override
            public CalDavService getIfUnique() {
                return service;
            }

            @Override
            public CalDavService getObject() {
                return service;
            }
        };
    }

    private static CalendarProperties calendarProperties(final boolean enabled) {
        return new CalendarProperties(
                enabled,
                "https://files.example.test/caldav/",
                "https://files.example.test/caldav/collection/bookings/",
                "thonbecker",
                "opencloud/calendar-app-tokens",
                "booking_app_token",
                "us-east-1",
                "org@example.com");
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
