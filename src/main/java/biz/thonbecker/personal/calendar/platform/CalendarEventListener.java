package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens for booking events and manages corresponding CalDAV calendar entries.
 *
 * <p>Uses {@link ApplicationModuleListener} so Spring Modulith persists the event publication,
 * invokes the listener after commit, and retries it if the integration work fails.
 */
@Component
@Slf4j
class CalendarEventListener {

    private final CalDavService calDavService;
    private final CalendarEventMappingRepository mappingRepository;
    private final CalendarProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    CalendarEventListener(
            final ObjectProvider<CalDavService> calDavService,
            final CalendarEventMappingRepository mappingRepository,
            final CalendarProperties properties,
            final ApplicationEventPublisher eventPublisher) {
        this.calDavService = calDavService.getIfAvailable();
        this.mappingRepository = mappingRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onBookingCreated(final BookingCreatedEvent event) {
        if (!properties.enabled() || Objects.isNull(calDavService)) {
            log.debug("CalDAV integration disabled, skipping calendar event creation");
            return;
        }

        final String calendarUid;
        try {
            calendarUid = calDavService.createEvent(event);
        } catch (final RuntimeException exception) {
            reportFailure("calendar.booking-created", exception);
            throw exception;
        }

        final var mapping =
                mappingRepository.findByBookingId(event.bookingId()).orElseGet(CalendarEventMappingEntity::new);
        mapping.setBookingId(event.bookingId());
        mapping.setCalendarUid(calendarUid);
        mapping.setCalendarHref(calendarUid + ".ics");
        mappingRepository.save(mapping);

        log.info("Created CalDAV event {} for booking {}", calendarUid, event.confirmationCode());
    }

    @ApplicationModuleListener
    void onBookingCancelled(final BookingCancelledEvent event) {
        if (!properties.enabled() || Objects.isNull(calDavService)) {
            return;
        }

        final var mapping = mappingRepository.findByBookingId(event.bookingId());
        final var calendarUid =
                mapping.map(CalendarEventMappingEntity::getCalendarUid).orElse("booking-" + event.confirmationCode());

        try {
            calDavService.deleteEvent(calendarUid);
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
            log.warn("Failed to report CalDAV exception", reportingFailure);
        }
    }
}
