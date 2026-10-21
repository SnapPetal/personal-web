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

/**
 * Explains booking requests that Spring rejects before {@link BookingController} runs. Without this,
 * the visitor gets Spring's default 400 body with no reason, and error tracking gets nothing.
 *
 * <p>The reported message names fields and constraints only. It never copies the rejected values,
 * because an invalid email address does not match the redaction pattern in
 * {@link ServerExceptionReportedEvent}.
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
        final var message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("Please check the booking details and try again.");

        report(exception, "Booking request failed validation: " + violations);
        return ResponseEntity.badRequest().body(new BookingErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<BookingErrorResponse> unreadableRequest(final HttpMessageNotReadableException exception) {
        final var field = exception.getCause() instanceof JacksonException jackson
                ? jackson.getPath().stream()
                        .map(JacksonException.Reference::getPropertyName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."))
                : "";
        final var detail = field.isEmpty() ? "body" : field;

        report(exception, "Booking request could not be read: " + detail);
        return ResponseEntity.badRequest()
                .body(new BookingErrorResponse("We could not read the booking details. Please try again."));
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
