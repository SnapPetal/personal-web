package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingChatAnswer;
import biz.thonbecker.personal.booking.platform.web.model.BookingChatSlot;
import biz.thonbecker.personal.booking.platform.web.model.PublicAvailabilitySlot;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Answers a visitor from open booking slots. The model never receives, and the reply never returns, a
 * time that {@link PublicAvailabilityService} did not just read from {@code BookingService}.
 */
@Service
@Slf4j
public class BookingChatService {

    static final String NO_OPENINGS = "There are no available openings in the next 14 days.";

    private static final String SYSTEM_PROMPT = """
            You are a concise booking availability assistant for Thon Becker.
            Answer only questions about available meeting times using the supplied availability data.
            You cannot create, cancel, or modify bookings, and you cannot access attendee records or accounts.
            Do not discuss implementation details, providers, infrastructure, or internal technology.
            If a visitor asks about anything else, politely say you can only help find available meeting times.
            Never claim to have taken an action or accessed private information.
            """;

    private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final PublicAvailabilityService availabilityService;
    private final BookingChatAnswerParser parser;
    private final BookingChatProperties properties;
    private final Clock clock;
    private final @Nullable ChatModel chatModel;

    @Autowired
    public BookingChatService(
            final PublicAvailabilityService availabilityService,
            final BookingChatAnswerParser parser,
            final BookingChatProperties properties,
            final ObjectProvider<ChatModel> chatModels) {
        this(availabilityService, parser, properties, Clock.systemUTC(), chatModels.getIfAvailable());
    }

    BookingChatService(
            final PublicAvailabilityService availabilityService,
            final BookingChatAnswerParser parser,
            final BookingChatProperties properties,
            final Clock clock,
            final @Nullable ChatModel chatModel) {
        this.availabilityService = availabilityService;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
        this.chatModel = chatModel;
    }

    public BookingChatAnswer answer(final String message, final String timezone) {
        final var zone = ZoneId.of(timezone);
        final var today = LocalDate.now(clock.withZone(zone));
        final var availability = availabilityService.availability(today, today.plusDays(13), timezone);
        final var slots = unique(availability.slots());
        if (slots.isEmpty()) {
            log.info("Public booking chat has no open slots");
            return new BookingChatAnswer(NO_OPENINGS, "show_availability", List.of());
        }
        if (Objects.isNull(chatModel)) {
            throw new IllegalStateException("Chat model is not configured");
        }

        log.info("Answering public booking chat from {} open slots", slots.size());
        final var prompt = new Prompt(
                List.of(
                        new SystemMessage(instructions(timezone)),
                        new UserMessage(userPrompt(message, timezone, today, schedule(slots, zone)))),
                BedrockChatOptions.builder()
                        .model(properties.getModel())
                        .maxTokens(properties.getMaxTokens())
                        .temperature(properties.getTemperature())
                        .build());
        final var response = chatModel.call(prompt);
        final var result = Objects.isNull(response) ? null : response.getResult();
        final var text = Objects.isNull(result) ? null : result.getOutput().getText();
        if (Objects.isNull(text) || text.isBlank()) {
            throw new IllegalStateException("Chat model returned an empty response");
        }
        return ground(parser.parse(text), slots, timezone);
    }

    private BookingChatAnswer ground(
            final BookingChatDraft draft, final List<PublicAvailabilitySlot> slots, final String timezone) {
        final var allowed = new LinkedHashMap<String, BookingChatSlot>();
        for (final var slot : slots) {
            allowed.putIfAbsent(
                    key(slot.start(), slot.end()),
                    new BookingChatSlot(formatOffset(slot.start()), formatOffset(slot.end()), timezone));
        }

        final var kept = new ArrayList<BookingChatSlot>();
        final var seen = new LinkedHashSet<String>();
        for (final var candidate : draft.availableSlots()) {
            try {
                final var slotKey = key(OffsetDateTime.parse(candidate.start()), OffsetDateTime.parse(candidate.end()));
                final var canonical = allowed.get(slotKey);
                if (Objects.nonNull(canonical) && seen.add(slotKey)) {
                    kept.add(canonical);
                }
            } catch (final DateTimeException ignored) {
                // The model offered a time that is not on the schedule.
            }
        }
        return new BookingChatAnswer(draft.message(), draft.action(), List.copyOf(kept));
    }

    private static List<PublicAvailabilitySlot> unique(final List<PublicAvailabilitySlot> slots) {
        final var seen = new LinkedHashSet<String>();
        final var unique = new ArrayList<PublicAvailabilitySlot>();
        for (final var slot : slots) {
            if (seen.add(key(slot.start(), slot.end()))) {
                unique.add(slot);
            }
        }
        return List.copyOf(unique);
    }

    private static String schedule(final List<PublicAvailabilitySlot> slots, final ZoneId zone) {
        final var timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
        final var dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US);
        final var days = new LinkedHashMap<LocalDate, List<PublicAvailabilitySlot>>();
        for (final var slot : slots) {
            final var localDate = slot.start().atZoneSameInstant(zone).toLocalDate();
            days.computeIfAbsent(localDate, ignored -> new ArrayList<>()).add(slot);
        }

        final var text = new StringBuilder("Available Openings Schedule (in ")
                .append(zone.getId())
                .append("):\n");
        for (final var entry : days.entrySet()) {
            text.append(dateFormatter.format(entry.getKey()))
                    .append(" (")
                    .append(entry.getKey())
                    .append("):\n");
            for (final var slot : entry.getValue()) {
                final var local = slot.start().atZoneSameInstant(zone);
                text.append(" - ")
                        .append(timeFormatter.format(local))
                        .append(" [start: \"")
                        .append(formatOffset(slot.start()))
                        .append("\", end: \"")
                        .append(formatOffset(slot.end()))
                        .append("\"]\n");
            }
        }
        return text.toString();
    }

    private static String instructions(final String timezone) {
        return SYSTEM_PROMPT + """

                You MUST respond with ONLY a valid JSON object matching this schema:
                {
                  "message": "Complete, conversational answer mentioning specific dates and times from the schedule.",
                  "action": "show_availability" | "clarify" | "unsupported",
                  "availableSlots": [
                    { "start": "YYYY-MM-DDTHH:MM:SS+ZZ:ZZ", "end": "YYYY-MM-DDTHH:MM:SS+ZZ:ZZ", "timezone": "%s" }
                  ]
                }

                CRITICAL RULES:
                1. "message" is a complete friendly sentence. Never end it with a colon.
                2. If the visitor asks for a time that is not on the schedule, say there is no opening then and offer the closest openings that are.
                3. "availableSlots" contains at most 6 objects copied from the schedule. Use an empty array for off-topic questions.
                4. Never invent dates, times, or slots. Only openings listed below exist.
                5. Do not wrap the JSON in markdown or add text outside the object.
                """.formatted(timezone);
    }

    private static String userPrompt(
            final String message, final String timezone, final LocalDate today, final String schedule) {
        return """
                Visitor timezone: %s
                Current visitor date: %s

                %s
                Visitor message: %s
                """.formatted(timezone, today, schedule, message);
    }

    private static String formatOffset(final OffsetDateTime value) {
        return value.format(OFFSET_FORMAT);
    }

    private static String key(final OffsetDateTime start, final OffsetDateTime end) {
        return start.toInstant() + "|" + end.toInstant();
    }
}
