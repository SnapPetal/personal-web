package biz.thonbecker.personal.booking.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Accepts the public sites, the host that served the page, and non-browser clients with no Origin. */
@Component
class BookingChatOriginGuard {

    private final BookingChatProperties properties;

    BookingChatOriginGuard(final BookingChatProperties properties) {
        this.properties = properties;
    }

    boolean allowed(final HttpServletRequest request) {
        final var origin = request.getHeader("Origin");
        if (Objects.isNull(origin) || origin.isBlank()) {
            return true;
        }
        if (properties.getAllowedOrigins().contains(origin)) {
            return true;
        }
        final var host = request.getHeader("Host");
        if (Objects.isNull(host) || host.isBlank()) {
            return false;
        }
        return origin.equalsIgnoreCase(request.getScheme() + "://" + host);
    }
}
