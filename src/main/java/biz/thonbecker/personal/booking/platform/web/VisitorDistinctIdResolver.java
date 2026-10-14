package biz.thonbecker.personal.booking.platform.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the visitor's PostHog distinct id from the request so server events can join browser events.
 *
 * <p>Prefers {@value #HEADER_NAME}. Falls back to the {@code ph_*_posthog} cookie written by
 * posthog.js, then the server {@code posthog_distinct_id} cookie. A missing or shared id is empty
 * so callers skip the event instead of merging every visitor into one person.
 */
@Component
class VisitorDistinctIdResolver {

    static final String HEADER_NAME = "X-PostHog-Distinct-Id";
    static final String SHARED_ANONYMOUS_ID = "booking-anonymous";

    private static final Pattern SAFE_DISTINCT_ID = Pattern.compile("^[A-Za-z0-9._:@+\\-]{1,200}$");
    private static final Pattern COOKIE_DISTINCT_ID =
            Pattern.compile("\"distinct_id\"\\s*:\\s*\"([A-Za-z0-9._:@+\\-]{1,200})\"");

    Optional<String> resolve(final HttpServletRequest request) {
        final var header = sanitize(request.getHeader(HEADER_NAME));
        if (header.isPresent()) {
            return header;
        }

        final var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        for (final Cookie cookie : cookies) {
            if (cookie == null || cookie.getName() == null) {
                continue;
            }
            final var fromCookie = distinctIdFromCookie(cookie.getName(), cookie.getValue());
            if (fromCookie.isPresent()) {
                return fromCookie;
            }
        }
        return Optional.empty();
    }

    private Optional<String> distinctIdFromCookie(final String name, final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if ("posthog_distinct_id".equals(name)) {
            return sanitize(decode(value));
        }
        if (!name.startsWith("ph_") || !name.endsWith("_posthog")) {
            return Optional.empty();
        }

        final var decoded = decode(value);
        final var matcher = COOKIE_DISTINCT_ID.matcher(decoded);
        if (matcher.find()) {
            return sanitize(matcher.group(1));
        }
        return Optional.empty();
    }

    private String decode(final String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException exception) {
            return value;
        }
    }

    private Optional<String> sanitize(final String candidate) {
        if (candidate == null || candidate.isBlank() || SHARED_ANONYMOUS_ID.equals(candidate)) {
            return Optional.empty();
        }
        final var trimmed = candidate.trim();
        if (!SAFE_DISTINCT_ID.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
}
