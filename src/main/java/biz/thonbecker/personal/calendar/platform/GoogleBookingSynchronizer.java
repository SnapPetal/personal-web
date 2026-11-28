package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCalendarAccess;
import biz.thonbecker.personal.booking.api.BookingCancellationRequestedEvent;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingEntity;
import biz.thonbecker.personal.calendar.platform.persistence.CalendarEventMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@RequiredArgsConstructor
class GoogleBookingSynchronizer {
    private final BookingCalendarAccess bookings;
    private final CalendarEventMappingRepository mappings;
    private final GoogleCalendarClient google;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public void synchronize(final Long bookingId, final boolean checkDeletion) {
        final var booking = bookings.lockActiveBooking(bookingId);
        if (booking.isEmpty()) {
            return;
        }
        final var session = google.session();
        final var mapping = mappings.findByBookingId(bookingId).orElseGet(CalendarEventMappingEntity::new);
        final var marker = mapping.getCalendarHref();
        if (marker != null && marker.startsWith("google:")) {
            requireSameCalendar(marker, google.marker(session));
            if (checkDeletion && !google.exists(session, bookingId)) {
                publisher.publishEvent(new BookingCancellationRequestedEvent(bookingId));
            }
            return;
        }
        google.create(session, booking.get());
        mapping.setBookingId(bookingId);
        mapping.setCalendarUid(google.eventId(bookingId));
        mapping.setCalendarHref(google.marker(session));
        mappings.save(mapping);
    }

    @Transactional
    public void cancel(final Long bookingId) {
        if (bookings.lockActiveBooking(bookingId).isPresent()) {
            return;
        }
        final var session = google.session();
        final var mapping = mappings.findByBookingId(bookingId);
        mapping.ifPresent(found -> {
            final var marker = found.getCalendarHref();
            if (marker != null && marker.startsWith("google:")) {
                requireSameCalendar(marker, google.marker(session));
            }
        });
        google.delete(session, bookingId);
        mapping.ifPresent(mappings::delete);
    }

    private static void requireSameCalendar(final String stored, final String current) {
        if (!stored.equals(current)) {
            throw new IllegalStateException("Google calendar changed; reconcile mappings before continuing");
        }
    }
}
