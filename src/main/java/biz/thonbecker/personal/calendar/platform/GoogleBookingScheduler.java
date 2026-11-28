package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCalendarAccess;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class GoogleBookingScheduler {
    private final BookingCalendarAccess bookings;
    private final GoogleBookingSynchronizer synchronizer;
    private final GoogleCalendarClient google;
    private final ApplicationEventPublisher publisher;

    @Scheduled(cron = "${calendar.google.sync-cron:0 */5 * * * *}")
    @SchedulerLock(name = "syncGoogleBookingCalendar", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void sync() {
        try {
            google.session();
        } catch (final RuntimeException exception) {
            log.error("Google Calendar authorization check failed", exception);
            publisher.publishEvent(ServerExceptionReportedEvent.from("calendar.google-authorization", exception));
            return;
        }
        for (final var bookingId : bookings.activeFutureBookingIds()) {
            try {
                synchronizer.synchronize(bookingId, true);
            } catch (final RuntimeException exception) {
                log.error("Google Calendar sync failed for booking {}", bookingId, exception);
                publisher.publishEvent(ServerExceptionReportedEvent.from("calendar.google-sync", exception));
            }
        }
        for (final var bookingId : bookings.cancelledFutureBookingIds()) {
            try {
                synchronizer.cancel(bookingId);
            } catch (final RuntimeException exception) {
                log.error("Google Calendar cancellation sync failed for booking {}", bookingId, exception);
                publisher.publishEvent(ServerExceptionReportedEvent.from("calendar.google-cancel", exception));
            }
        }
    }
}
