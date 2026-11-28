package biz.thonbecker.personal.booking.api;

import java.util.List;
import java.util.Optional;

/**
 * Booking snapshots for calendar synchronization. The caller must hold a transaction
 * when acquiring a booking lock, until its calendar changes have been recorded.
 */
public interface BookingCalendarAccess {
    List<Long> activeFutureBookingIds();

    List<Long> cancelledFutureBookingIds();

    Optional<BookingCreatedEvent> lockActiveBooking(Long bookingId);
}
