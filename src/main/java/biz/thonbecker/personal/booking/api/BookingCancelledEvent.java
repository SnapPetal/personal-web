package biz.thonbecker.personal.booking.api;

import java.time.LocalDateTime;

/**
 * Event published when a booking is cancelled.
 *
 * <p>Contains all data needed for notification sending without requiring
 * additional lookups from the booking module.
 *
 * @param bookingId Booking identifier
 * @param confirmationCode Booking confirmation code
 * @param attendeeEmail Attendee's email address
 * @param attendeeName Attendee's name
 * @param bookingTypeName Type of booking
 * @param startTime Scheduled start time
 * @param endTime Scheduled end time
 * @param visitorDistinctId PostHog distinct id for the visitor who cancelled the booking, when known
 */
public record BookingCancelledEvent(
        Long bookingId,
        String confirmationCode,
        String attendeeEmail,
        String attendeeName,
        String bookingTypeName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String visitorDistinctId) {}
