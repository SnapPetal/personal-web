package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingChatAnswer;
import biz.thonbecker.personal.booking.platform.web.model.BookingChatError;
import biz.thonbecker.personal.booking.platform.web.model.BookingChatSlotButton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.databind.JsonNode;

/**
 * Public booking assistant. HTMX on the booking page posts the form. A JSON body is the same contract
 * the previous Worker exposed at {@code /chat}.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
class BookingChatController {

    private static final DateTimeFormatter SLOT_LABEL = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a z", Locale.US);

    private final BookingChatService bookingChatService;
    private final BookingChatRateLimiter rateLimiter;
    private final BookingChatOriginGuard originGuard;
    private final BookingChatProperties properties;

    @PostMapping(value = "/booking/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<?> chatJson(@RequestBody final JsonNode body, final HttpServletRequest request) {
        if (Objects.isNull(body) || !body.isObject()) {
            return jsonError(400, "Request body must be JSON");
        }

        final var messageNode = body.get("message");
        final String message = Objects.nonNull(messageNode) && messageNode.isTextual() ? messageNode.asText() : null;
        final var timezoneNode = body.get("timezone");
        final String timezone;
        if (Objects.isNull(timezoneNode) || timezoneNode.isNull()) {
            timezone = null;
        } else if (!timezoneNode.isTextual()) {
            timezone = "not-a-timezone";
        } else {
            timezone = timezoneNode.asText();
        }
        return toJson(admit(message, timezone, request));
    }

    @PostMapping(value = "/booking/chat", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String chatForm(
            @RequestParam(name = "message", required = false) final String message,
            @RequestParam(name = "timezone", required = false) final String timezone,
            final Model model,
            final HttpServletRequest request,
            final HttpServletResponse response) {
        final var outcome = admit(message, timezone, request);
        if (Objects.nonNull(outcome.error())) {
            response.setStatus(outcome.status());
            model.addAttribute("chatError", outcome.error());
            return "booking/fragments :: chat-reply";
        }
        model.addAttribute("chatReply", outcome.answer());
        model.addAttribute("chatSlots", buttons(outcome.answer()));
        return "booking/fragments :: chat-reply";
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    ResponseEntity<BookingChatError> unreadableMessage() {
        return jsonError(400, "Request body must be JSON");
    }

    private ResponseEntity<?> toJson(final BookingChatOutcome outcome) {
        if (Objects.nonNull(outcome.error())) {
            return jsonError(outcome.status(), outcome.error());
        }
        return ResponseEntity.ok(outcome.answer());
    }

    private ResponseEntity<BookingChatError> jsonError(final int status, final String error) {
        return ResponseEntity.status(status).body(new BookingChatError(error));
    }

    private BookingChatOutcome admit(final String message, final String timezone, final HttpServletRequest request) {
        if (!originGuard.allowed(request)) {
            log.warn("Rejected public booking chat from origin {}", request.getHeader("Origin"));
            return BookingChatOutcome.error(403, "Origin not allowed");
        }
        if (rateLimiter.isLimited(clientIp(request))) {
            return BookingChatOutcome.error(429, "Please try again later");
        }

        if (Objects.isNull(message) || message.isBlank() || message.length() > properties.getMaxMessageLength()) {
            return BookingChatOutcome.error(
                    400, "message is required and must be at most " + properties.getMaxMessageLength() + " characters");
        }

        final var resolvedTimezone = Objects.isNull(timezone) ? "America/Chicago" : timezone;
        if (resolvedTimezone.length() > properties.getMaxTimezoneLength() || !validTimezone(resolvedTimezone)) {
            return BookingChatOutcome.error(400, "timezone must be a valid IANA timezone");
        }

        try {
            log.info("public_chat_request messageLength={}", message.trim().length());
            return BookingChatOutcome.ok(bookingChatService.answer(message.trim(), resolvedTimezone));
        } catch (final RuntimeException exception) {
            log.error("Public booking chat failed", exception);
            return BookingChatOutcome.error(503, "The chat is temporarily unavailable");
        }
    }

    private static boolean validTimezone(final String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (final DateTimeException exception) {
            return false;
        }
    }

    private static String clientIp(final HttpServletRequest request) {
        final var cloudflare = request.getHeader("CF-Connecting-IP");
        if (Objects.nonNull(cloudflare) && !cloudflare.isBlank()) {
            return cloudflare.split(",")[0].trim();
        }
        final var remote = request.getRemoteAddr();
        return Objects.nonNull(remote) && !remote.isBlank() ? remote : "unknown";
    }

    private static List<BookingChatSlotButton> buttons(final BookingChatAnswer answer) {
        return answer.availableSlots().stream()
                .map(slot -> new BookingChatSlotButton(slot.start(), label(slot.start(), slot.timezone())))
                .toList();
    }

    private static String label(final String start, final String timezone) {
        try {
            final var zoned = OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.of(timezone));
            return SLOT_LABEL.format(zoned);
        } catch (final DateTimeException exception) {
            return start;
        }
    }
}
