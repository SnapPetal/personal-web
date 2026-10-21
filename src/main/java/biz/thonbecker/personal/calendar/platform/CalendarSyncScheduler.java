package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the configured CalDAV collection for deleted events.
 *
 * <p>When a calendar event is deleted from the collection, this scheduler detects it and publishes a
 * {@link BookingCancellationRequestedEvent} so the booking module can cancel the corresponding booking.
 */
@Service
@ConditionalOnProperty(name = "calendar.caldav.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class CalendarSyncScheduler {

    private final CalDavService calDavService;
    private final CalendarEventMappingRepository mappingRepository;
    private final ApplicationEventPublisher eventPublisher;

    @PostConstruct
    void init() {
        try {
            calDavService.verifyCalendarCollection();
        } catch (final Exception exception) {
            log.warn("Failed to verify CalDAV collection on startup: {}", exception.getMessage());
        }
    }

    @Scheduled(cron = "${calendar.caldav.sync-cron:0 */5 * * * *}")
    @SchedulerLock(name = "syncCalDavCalendar", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    @Transactional
    public void syncCalendarDeletions() {
        log.debug("Checking CalDAV collection for deleted events...");

        try {
            final var existingUids = calDavService.listEventResources();
            final var allMappings = mappingRepository.findAll();

            var deletedCount = 0;
            for (final var mapping : allMappings) {
                if (!existingUids.contains(mapping.getCalendarUid())) {
                    log.info(
                            "Calendar event {} deleted from CalDAV, requesting cancellation for booking {}",
                            mapping.getCalendarUid(),
                            mapping.getBookingId());

                    eventPublisher.publishEvent(new BookingCancellationRequestedEvent(mapping.getBookingId()));
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                log.info("Detected {} deleted calendar events, published cancellation requests", deletedCount);
            } else {
                log.debug("No deleted calendar events detected");
            }
        } catch (final Exception exception) {
            log.error("Failed to sync CalDAV collection: {}", exception.getMessage(), exception);
        }
    }
}
