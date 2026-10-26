package biz.thonbecker.personal.booking.platform.web;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed-window limit for the public booking chat. Ten requests per minute per address, matching the
 * previous Worker.
 */
@Component
class BookingChatRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    BookingChatRateLimiter(final BookingChatProperties properties) {
        this(properties.getMaxRequestsPerWindow(), properties.getWindow(), Clock.systemUTC());
    }

    BookingChatRateLimiter(final int maxRequests, final Duration window, final Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    boolean isLimited(final String key) {
        final var now = clock.millis();
        final var state = windows.compute(key, (ignored, current) -> {
            if (current == null || current.resetAt() <= now) {
                return new Window(1, now + window.toMillis());
            }
            return new Window(current.count() + 1, current.resetAt());
        });
        if (windows.size() >= 1024) {
            windows.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
        }
        return state.count() > maxRequests;
    }

    private record Window(int count, long resetAt) {}
}
