package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.api.BookingType;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.booking.platform.web.model.PublicAvailabilityResponse;
import biz.thonbecker.personal.booking.platform.web.model.PublicAvailabilitySlot;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only availability for the public booking page and its assistant.
 *
 * <p>Slots are read from {@link BookingService} in-process. The assistant must not load them over HTTP.
 */
@Service
@RequiredArgsConstructor
public class PublicAvailabilityService {

    static final ZoneId BOOKING_ZONE = ZoneId.of("America/Chicago");
    static final int MAX_PUBLIC_AVAILABILITY_DAYS = 14;

    private final BookingService bookingService;

    /**
     * @param from first visitor-local date, inclusive
     * @param to last visitor-local date, inclusive
     * @param timezone visitor IANA timezone
     * @return open slots converted to the visitor timezone
     */
    public PublicAvailabilityResponse availability(final LocalDate from, final LocalDate to, final String timezone) {
        final var visitorZone = ZoneId.of(timezone);
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_PUBLIC_AVAILABILITY_DAYS) {
            throw new IllegalArgumentException("Availability window must be within 14 days");
        }

        final var visitorStart = from.atStartOfDay(visitorZone);
        final var visitorEnd = to.plusDays(1).atStartOfDay(visitorZone);
        final var sourceStart =
                visitorStart.withZoneSameInstant(BOOKING_ZONE).toLocalDate().minusDays(1);
        final var sourceEnd =
                visitorEnd.withZoneSameInstant(BOOKING_ZONE).toLocalDate().plusDays(1);
        final Map<Long, BookingType> bookingTypes = bookingService.getActiveBookingTypes().stream()
                .collect(Collectors.toMap(BookingType::id, Function.identity()));
        final var slots = new ArrayList<PublicAvailabilitySlot>();

        for (var date = sourceStart; !date.isAfter(sourceEnd); date = date.plusDays(1)) {
            for (var bookingType : bookingTypes.values()) {
                bookingService.getAvailableSlots(bookingType.id(), date).forEach(slot -> {
                    final ZonedDateTime start =
                            slot.startTime().atZone(BOOKING_ZONE).withZoneSameInstant(visitorZone);
                    final ZonedDateTime end =
                            slot.endTime().atZone(BOOKING_ZONE).withZoneSameInstant(visitorZone);
                    if (!start.isBefore(visitorStart) && end.isBefore(visitorEnd)) {
                        slots.add(new PublicAvailabilitySlot(
                                bookingType.id(),
                                bookingType.name(),
                                bookingType.durationMinutes(),
                                OffsetDateTime.from(start),
                                OffsetDateTime.from(end)));
                    }
                });
            }
        }

        slots.sort(Comparator.comparing(PublicAvailabilitySlot::start));
        return new PublicAvailabilityResponse(timezone, slots);
    }
}
