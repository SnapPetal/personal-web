package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarProvider;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Polls Google Calendar for booking events that were deleted.
 *
 * <p>A booking is cancelled only when Google reports its mapped event as deleted. An event that Google
 * does not know, or a mapping from the retired OpenCloud calendar, never cancels a booking. The run
 * reads the state of every mapped event before it publishes anything, so a failed read skips the run.
 */
@Service
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class CalendarSyncScheduler {

    private final GoogleCalendarService calendarService;
    private final CalendarEventMappingRepository mappingRepository;
    private final ApplicationEventPublisher eventPublisher;

    @PostConstruct
    void init() {
        try {
            calendarService.verifyCalendar();
        } catch (final RuntimeException exception) {
            log.warn("Failed to verify Google Calendar on startup: {}", exception.getMessage());
            reportFailure("calendar.verify", exception);
        }
    }

    @Scheduled(cron = "${calendar.google.sync-cron:0 */5 * * * *}")
    @SchedulerLock(name = "syncGoogleBookingCalendar", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void syncCalendarDeletions() {
        log.debug("Checking Google Calendar for deleted booking events...");

        try {
            final var deletedBookingIds = new ArrayList<Long>();
            for (final var mapping : mappingRepository.findByCalendarProvider(CalendarProvider.GOOGLE)) {
                switch (calendarService.eventState(mapping.getCalendarUid())) {
                    case DELETED -> deletedBookingIds.add(mapping.getBookingId());
                    case MISSING ->
                        log.warn(
                                "Google Calendar has no event {} for booking {}, leaving the booking unchanged",
                                mapping.getCalendarUid(),
                                mapping.getBookingId());
                    case ACTIVE -> {}
                }
            }

            for (final var bookingId : deletedBookingIds) {
                log.info("Calendar event for booking {} was deleted, requesting cancellation", bookingId);
                eventPublisher.publishEvent(new BookingCancellationRequestedEvent(bookingId));
            }

            if (deletedBookingIds.isEmpty()) {
                log.debug("No deleted calendar events detected");
            }
        } catch (final RuntimeException exception) {
            log.error("Failed to sync Google Calendar, skipping this run: {}", exception.getMessage(), exception);
            reportFailure("calendar.sync", exception);
        }
    }

    private void reportFailure(final String source, final RuntimeException exception) {
        try {
            eventPublisher.publishEvent(ServerExceptionReportedEvent.from(source, exception));
        } catch (final RuntimeException reportingFailure) {
            log.warn("Failed to report calendar exception", reportingFailure);
        }
    }
}
