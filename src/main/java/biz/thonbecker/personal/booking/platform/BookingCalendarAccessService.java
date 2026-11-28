package biz.thonbecker.personal.booking.platform;

import biz.thonbecker.personal.booking.api.BookingCalendarAccess;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.booking.api.BookingStatus;
import biz.thonbecker.personal.booking.platform.persistence.BookingRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class BookingCalendarAccessService implements BookingCalendarAccess {
    private final BookingRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<Long> activeFutureBookingIds() {
        return repository.findActiveFutureIds(LocalDateTime.now(ZoneId.of("America/Chicago")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> cancelledFutureBookingIds() {
        return repository.findCancelledFutureIds(LocalDateTime.now(ZoneId.of("America/Chicago")));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BookingCreatedEvent> lockActiveBooking(final Long bookingId) {
        return repository
                .lockById(bookingId)
                .filter(booking ->
                        booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.PENDING)
                .map(booking -> new BookingCreatedEvent(
                        booking.getId(),
                        booking.getConfirmationCode(),
                        booking.getAttendeeEmail(),
                        booking.getAttendeeName(),
                        booking.getAttendeePhone(),
                        booking.getBookingType().getName(),
                        booking.getStartTime(),
                        booking.getEndTime(),
                        booking.getMessage(),
                        null));
    }
}
