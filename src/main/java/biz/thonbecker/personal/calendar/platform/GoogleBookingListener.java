package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class GoogleBookingListener {
    private final GoogleBookingSynchronizer synchronizer;
    private final ApplicationEventPublisher publisher;

    @ApplicationModuleListener
    void created(final BookingCreatedEvent event) {
        try {
            synchronizer.synchronize(event.bookingId(), false);
        } catch (final RuntimeException exception) {
            report("calendar.google-created", exception);
            throw exception;
        }
    }

    @ApplicationModuleListener
    void cancelled(final BookingCancelledEvent event) {
        try {
            synchronizer.cancel(event.bookingId());
        } catch (final RuntimeException exception) {
            report("calendar.google-cancelled", exception);
            throw exception;
        }
    }

    private void report(final String source, final RuntimeException exception) {
        try {
            publisher.publishEvent(ServerExceptionReportedEvent.from(source, exception));
        } catch (final RuntimeException reportingFailure) {
            log.warn("Failed to report Google Calendar error", reportingFailure);
        }
    }
}
