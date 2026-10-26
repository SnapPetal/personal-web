package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingChatSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Turns model text into a draft reply. Slots that are not well-formed objects are dropped. */
@Component
class BookingChatAnswerParser {

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private static final String DEFAULT_MESSAGE = "Here are the available times.";

    private final ObjectMapper objectMapper;

    BookingChatAnswerParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    BookingChatDraft parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new BookingChatDraft(DEFAULT_MESSAGE, "show_availability", List.of());
        }

        try {
            final var parsed = objectMapper.readTree(extractJson(raw));
            if (parsed.isTextual()) {
                return new BookingChatDraft(cleanMessage(parsed.asText()), "show_availability", List.of());
            }
            if (parsed.isObject()) {
                return fromObject(unwrap(parsed));
            }
        } catch (final RuntimeException ignored) {
            return new BookingChatDraft(cleanMessage(raw), "show_availability", List.of());
        }

        return new BookingChatDraft(cleanMessage(raw), "show_availability", List.of());
    }

    private JsonNode unwrap(final JsonNode node) {
        final var response = node.get("response");
        if (response == null || response.isNull()) {
            return node;
        }
        if (response.isObject()) {
            return response;
        }
        if (response.isTextual()) {
            try {
                final var nested = objectMapper.readTree(extractJson(response.asText()));
                if (nested.isObject()) {
                    return unwrap(nested);
                }
            } catch (final RuntimeException ignored) {
                return node;
            }
        }
        return node;
    }

    private BookingChatDraft fromObject(final JsonNode node) {
        final var messageNode = node.path("message");
        final var message = messageNode.isTextual() ? cleanMessage(messageNode.asText()) : DEFAULT_MESSAGE;
        final var actionNode = node.path("action");
        final var actionText = actionNode.isTextual() ? actionNode.asText() : "";
        final var action = switch (actionText) {
            case "show_availability", "clarify", "unsupported" -> actionText;
            default -> "show_availability";
        };
        final var slots = new ArrayList<BookingChatSlot>();
        final var rawSlots = node.path("availableSlots");
        if (rawSlots.isArray()) {
            for (final var slot : rawSlots) {
                if (!slot.isObject()) {
                    continue;
                }
                final var start = slot.path("start");
                final var end = slot.path("end");
                final var timezone = slot.path("timezone");
                if (start.isTextual() && end.isTextual() && timezone.isTextual()) {
                    slots.add(new BookingChatSlot(start.asText(), end.asText(), timezone.asText()));
                }
            }
        }
        return new BookingChatDraft(message, action, List.copyOf(slots));
    }

    private static String extractJson(final String raw) {
        final var trimmed = raw.trim();
        final var fence = CODE_FENCE.matcher(trimmed);
        if (fence.find()) {
            return fence.group(1).trim();
        }
        final var object = JSON_OBJECT.matcher(trimmed);
        if (object.find()) {
            return object.group().trim();
        }
        return trimmed;
    }

    private static String cleanMessage(final String message) {
        var cleaned = message == null ? "" : message.trim();
        if (cleaned.endsWith(":")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isEmpty() ? DEFAULT_MESSAGE : cleaned;
    }
}
