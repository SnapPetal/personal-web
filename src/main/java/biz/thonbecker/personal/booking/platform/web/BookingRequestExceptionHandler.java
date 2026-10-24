package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingErrorResponse;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.PropertyBindingException;

/**
 * Explains booking requests that Spring rejects before {@link BookingController} runs. Without this,
 * the visitor gets Spring's default 400 body with no reason, and error tracking gets nothing.
 *
 * <p>The response names the field and includes its constraint message, for example
 * {@code Invalid booking request: attendeeEmail: Please enter a valid email address.} The reported
 * message names fields and constraints only. It never copies the rejected values, because an invalid
 * email address does not match the redaction pattern in {@link ServerExceptionReportedEvent}.
 */
@RestControllerAdvice(assignableTypes = BookingController.class)
@RequiredArgsConstructor
@Slf4j
class BookingRequestExceptionHandler {

    private final ApplicationEventPublisher eventPublisher;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<BookingErrorResponse> invalidRequest(final MethodArgumentNotValidException exception) {
        final var fieldErrors = exception.getBindingResult().getFieldErrors();
        final var violations = fieldErrors.stream()
                .map(error -> error.getField() + " (" + error.getCode() + ")")
                .collect(Collectors.joining(", "));
        final var fields = fieldErrors.stream()
                .map(BookingRequestExceptionHandler::fieldError)
                .distinct()
                .collect(Collectors.joining("; "));
        final var message = fields.isEmpty() ? "The booking request is invalid." : "Invalid booking request: " + fields;
        final var report = violations.isEmpty()
                ? "Booking request failed validation"
                : "Booking request failed validation: " + violations;

        report(exception, report);
        return ResponseEntity.badRequest().body(new BookingErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<BookingErrorResponse> unreadableRequest(final HttpMessageNotReadableException exception) {
        final var field = unreadableField(exception);
        final var detail = Objects.isNull(field) ? "body" : field;
        final var message = Objects.isNull(field)
                ? "We could not read the booking details. Please try again."
                : "Invalid booking request: " + field;

        report(exception, "Booking request could not be read: " + detail);
        return ResponseEntity.badRequest().body(new BookingErrorResponse(message));
    }

    private static String fieldError(final FieldError error) {
        final var detail = error.getDefaultMessage();
        if (Objects.isNull(detail) || detail.isBlank()) {
            return error.getField();
        }
        return error.getField() + ": " + detail;
    }

    /**
     * Walks the Jackson cause chain. A binding failure can be wrapped more than once, and the
     * property name is the last non-blank segment on that path.
     */
    private static String unreadableField(final Throwable error) {
        String field = null;
        Throwable current = error;
        while (Objects.nonNull(current)) {
            if (current instanceof final PropertyBindingException binding) {
                final var property = binding.getPropertyName();
                if (Objects.nonNull(property) && !property.isBlank()) {
                    field = property;
                }
            } else if (current instanceof final JacksonException jackson) {
                final var path = jackson.getPath();
                if (Objects.nonNull(path)) {
                    for (var index = path.size() - 1; index >= 0; index--) {
                        final var name = path.get(index).getPropertyName();
                        if (Objects.nonNull(name) && !name.isBlank()) {
                            field = name;
                            break;
                        }
                    }
                }
            }
            current = current.getCause();
        }
        return field;
    }

    private void report(final Exception exception, final String safeMessage) {
        log.warn(safeMessage);
        try {
            final var reported = ServerExceptionReportedEvent.from("booking.create", exception);
            eventPublisher.publishEvent(new ServerExceptionReportedEvent(
                    reported.source(), reported.exceptionType(), safeMessage, reported.frames()));
        } catch (final RuntimeException reportingFailure) {
            log.warn("Failed to report booking request exception", reportingFailure);
        }
    }
}
