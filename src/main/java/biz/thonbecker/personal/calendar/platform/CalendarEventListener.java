package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarProvider;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens for booking events and manages corresponding Google Calendar entries.
 *
 * <p>Uses {@link ApplicationModuleListener} so Spring Modulith persists the event publication,
 * invokes the listener after commit, and retries it if the integration work fails.
 */
@Component
@Slf4j
class CalendarEventListener {

    private final GoogleCalendarService calendarService;
    private final CalendarEventMappingRepository mappingRepository;
    private final CalendarProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    CalendarEventListener(
            final ObjectProvider<GoogleCalendarService> calendarService,
            final CalendarEventMappingRepository mappingRepository,
            final CalendarProperties properties,
            final ApplicationEventPublisher eventPublisher) {
        this.calendarService = calendarService.getIfAvailable();
        this.mappingRepository = mappingRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onBookingCreated(final BookingCreatedEvent event) {
        if (!properties.enabled() || Objects.isNull(calendarService)) {
            log.debug("Google Calendar integration disabled, skipping calendar event creation");
            return;
        }

        final String eventId;
        try {
            eventId = calendarService.createEvent(event);
        } catch (final RuntimeException exception) {
            reportFailure("calendar.booking-created", exception);
            throw exception;
        }

        final var mapping =
                mappingRepository.findByBookingId(event.bookingId()).orElseGet(CalendarEventMappingEntity::new);
        mapping.setBookingId(event.bookingId());
        mapping.setCalendarUid(eventId);
        mapping.setCalendarProvider(CalendarProvider.GOOGLE);
        mappingRepository.save(mapping);

        log.info("Mapped Google Calendar event {} to booking {}", eventId, event.confirmationCode());
    }

    @ApplicationModuleListener
    void onBookingCancelled(final BookingCancelledEvent event) {
        if (!properties.enabled() || Objects.isNull(calendarService)) {
            return;
        }

        final var mapping = mappingRepository.findByBookingId(event.bookingId());
        final var eventId = GoogleCalendarService.eventId(event.confirmationCode());

        try {
            calendarService.deleteEvent(eventId);
        } catch (final RuntimeException exception) {
            reportFailure("calendar.booking-cancelled", exception);
            throw exception;
        }
        mapping.ifPresent(mappingRepository::delete);
    }

    private void reportFailure(final String source, final RuntimeException exception) {
        try {
            eventPublisher.publishEvent(ServerExceptionReportedEvent.from(source, exception));
        } catch (final RuntimeException reportingFailure) {
            log.warn("Failed to report calendar exception", reportingFailure);
        }
    }
}
