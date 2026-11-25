package biz.thonbecker.personal.shared.platform.configuration;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.ai.session.SessionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Removes expired Spring AI sessions and their conversation events. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMemoryCleanupService {

    private final SessionService sessionService;

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "cleanupSpringAiSessions", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void cleanupExpiredSessions() {
        final var deletedSessions = sessionService.deleteExpiredSessions(Instant.now());
        if (deletedSessions > 0) {
            log.info("Deleted {} expired Spring AI sessions", deletedSessions);
        }
    }
}
