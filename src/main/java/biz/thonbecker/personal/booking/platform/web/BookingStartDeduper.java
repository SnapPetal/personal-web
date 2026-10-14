package biz.thonbecker.personal.booking.platform.web;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Stops one visitor, or a replayed distinct id, from recording {@code booking_started} in a tight loop.
 */
@Component
class BookingStartDeduper {

    static final Duration DISTINCT_ID_COOLDOWN = Duration.ofMinutes(15);
    static final int MAX_STARTS_PER_MINUTE = 30;

    private static final int MAX_TRACKED_IDS = 10_000;

    private final ConcurrentHashMap<String, Instant> seenDistinctIds = new ConcurrentHashMap<>();
    private final AtomicInteger windowCount = new AtomicInteger();
    private long windowMinute = Long.MIN_VALUE;

    boolean begin(final String distinctId) {
        return begin(distinctId, Instant.now());
    }

    boolean begin(final String distinctId, final Instant now) {
        final var previous = seenDistinctIds.get(distinctId);
        if (previous != null && Duration.between(previous, now).compareTo(DISTINCT_ID_COOLDOWN) < 0) {
            return false;
        }
        if (!reserveWindowSlot(now)) {
            return false;
        }
        seenDistinctIds.put(distinctId, now);
        prune(now);
        return true;
    }

    private boolean reserveWindowSlot(final Instant now) {
        final var minute = now.getEpochSecond() / 60;
        synchronized (this) {
            if (minute != windowMinute) {
                windowMinute = minute;
                windowCount.set(0);
            }
            if (windowCount.get() >= MAX_STARTS_PER_MINUTE) {
                return false;
            }
            windowCount.incrementAndGet();
            return true;
        }
    }

    private void prune(final Instant now) {
        if (seenDistinctIds.size() <= MAX_TRACKED_IDS) {
            return;
        }
        seenDistinctIds
                .entrySet()
                .removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(DISTINCT_ID_COOLDOWN) >= 0);
    }
}
