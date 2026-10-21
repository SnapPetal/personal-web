package biz.thonbecker.personal.shared.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A server failure safe to forward to error tracking. The message is redacted so attendee addresses
 * and phone numbers are not copied out of the process.
 */
public record ServerExceptionReportedEvent(String source, String exceptionType, String message, List<Frame> frames) {

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_FRAMES = 30;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\b\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b");

    public ServerExceptionReportedEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(exceptionType, "exceptionType");
        Objects.requireNonNull(message, "message");
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
    }

    public static ServerExceptionReportedEvent from(final String source, final Throwable error) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(error, "error");
        final var exceptionType = error.getClass().getName();
        final var rawMessage = Objects.nonNull(error.getMessage()) ? error.getMessage() : exceptionType;
        final var frames = new ArrayList<Frame>();
        for (final var element : error.getStackTrace()) {
            if (frames.size() == MAX_FRAMES) {
                break;
            }
            final var fileName = Objects.nonNull(element.getFileName()) ? element.getFileName() : "Unknown";
            frames.add(new Frame(
                    element.getClassName() + "." + element.getMethodName(),
                    fileName,
                    Math.max(element.getLineNumber(), 0),
                    element.getClassName().startsWith("biz.thonbecker.")));
        }
        Collections.reverse(frames);
        return new ServerExceptionReportedEvent(source, exceptionType, redact(rawMessage), frames);
    }

    private static String redact(final String value) {
        final var withoutEmail = EMAIL.matcher(value).replaceAll("[redacted-email]");
        final var withoutPhone = PHONE.matcher(withoutEmail).replaceAll("[redacted-phone]");
        if (withoutPhone.length() <= MAX_MESSAGE_LENGTH) {
            return withoutPhone;
        }
        return withoutPhone.substring(0, MAX_MESSAGE_LENGTH);
    }

    /** One Java stack frame, oldest last so the throwing frame is the final entry. */
    public record Frame(String function, String filename, int line, boolean inApp) {}
}
