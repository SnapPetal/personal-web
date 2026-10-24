package biz.thonbecker.personal.booking.platform.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Request to create a new booking. Constraint messages are shown to the visitor.
 */
public record CreateBookingRequest(
        @NotNull(message = "Please choose a meeting type.") Long bookingTypeId,
        @NotBlank(message = "Please enter your name.") String attendeeName,

        @NotBlank(message = "Please enter your email address.") @Email(message = "Please enter a valid email address.")
        String attendeeEmail,

        String attendeePhone,
        @NotNull(message = "Please choose a time slot.") LocalDateTime startTime,
        String message) {}
