package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.api.BookingAvailabilityViewedEvent;
import biz.thonbecker.personal.booking.api.BookingStartedEvent;
import biz.thonbecker.personal.booking.api.BookingSubmittedEvent;
import biz.thonbecker.personal.booking.domain.exceptions.BookingTypeNotFoundException;
import biz.thonbecker.personal.booking.domain.exceptions.InvalidBookingException;
import biz.thonbecker.personal.booking.domain.exceptions.SlotNotAvailableException;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.booking.platform.web.model.BookingErrorResponse;
import biz.thonbecker.personal.booking.platform.web.model.CreateBookingRequest;
import biz.thonbecker.personal.booking.platform.web.model.PublicAvailabilityResponse;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Public web controller for booking functionality.
 */
@Controller
@RequestMapping("/booking")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private static final String BOOKING_STARTED_SESSION_ATTRIBUTE = "booking-started";

    private final BookingService bookingService;
    private final PublicAvailabilityService publicAvailabilityService;
    private final ApplicationEventPublisher eventPublisher;
    private final VisitorDistinctIdResolver visitorDistinctIdResolver;
    private final AutomatedProbeDetector automatedProbeDetector;
    private final BookingStartDeduper bookingStartDeduper;

    /**
     * Main booking page.
     *
     * <p>Rendering this page is not a booking start. Health checks, uptime monitors, load balancers,
     * and HTMX boosts all request it. {@code booking_started} is recorded by {@link #bookingStarted}
     * after a person chooses a meeting type.
     *
     * @param model Spring MVC model
     * @return Thymeleaf template name
     */
    @GetMapping
    public String bookingPage(final Model model) {
        final var bookingTypes = bookingService.getActiveBookingTypes();
        model.addAttribute("bookingTypes", bookingTypes);
        return "booking/index";
    }

    /**
     * Records that a person started a booking. One event per session and per visitor, and none for
     * monitors or partial requests.
     *
     * @param request current request
     * @return empty response whether or not an event was recorded
     */
    @PostMapping("/started")
    @ResponseBody
    public ResponseEntity<Void> bookingStarted(final HttpServletRequest request) {
        if (automatedProbeDetector.isAutomatedProbe(request) || request.getHeader("HX-Request") != null) {
            return ResponseEntity.noContent().build();
        }

        final var distinctId = visitorDistinctIdResolver.resolve(request);
        if (distinctId.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        final var session = request.getSession(true);
        if (session.getAttribute(BOOKING_STARTED_SESSION_ATTRIBUTE) != null) {
            return ResponseEntity.noContent().build();
        }
        if (!bookingStartDeduper.begin(distinctId.get())) {
            return ResponseEntity.noContent().build();
        }

        session.setAttribute(BOOKING_STARTED_SESSION_ATTRIBUTE, Boolean.TRUE);
        eventPublisher.publishEvent(new BookingStartedEvent(distinctId.get()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Get available slots for a booking type and date (HTMX endpoint).
     *
     * @param bookingTypeId Booking type identifier
     * @param date Date to check availability
     * @param model Spring MVC model
     * @return Thymeleaf fragment with time slots
     */
    @GetMapping("/types/{bookingTypeId}/slots")
    public String getAvailableSlots(
            @PathVariable final Long bookingTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate date,
            final Model model,
            final HttpServletRequest request,
            final HttpServletResponse response) {

        return renderAvailableSlots(bookingTypeId, date, model, request, response);
    }

    private String renderAvailableSlots(
            final Long bookingTypeId,
            final LocalDate date,
            final Model model,
            final HttpServletRequest request,
            final HttpServletResponse response) {

        try {
            log.debug("Fetching available slots for type {} on {}", bookingTypeId, date);
            final var slots = bookingService.getAvailableSlots(bookingTypeId, date);
            model.addAttribute("slots", slots);
            model.addAttribute("bookingTypeId", bookingTypeId);
            model.addAttribute("date", date);
            model.addAttribute("retryUrl", "/booking/types/" + bookingTypeId + "/slots?date=" + date);
            try {
                publishAvailabilityViewed(request, bookingTypeId, date, slots.size());
            } catch (final RuntimeException e) {
                log.warn("Failed to record booking availability analytics: {}", e.getMessage(), e);
            }
            log.info("Found {} available slots", slots.size());
        } catch (final BookingTypeNotFoundException e) {
            // Client error, not a transient failure: omit retryUrl so no "Try again" button repeats it.
            log.warn("Rejected availability request for unknown booking type {}", bookingTypeId);
            model.addAttribute("error", "That meeting type is no longer available. Please choose another.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } catch (final Exception e) {
            log.error("Failed to fetch available slots: {}", e.getMessage(), e);
            model.addAttribute("error", "Failed to load available time slots. Please try again.");
            model.addAttribute("retryUrl", "/booking/types/" + bookingTypeId + "/slots?date=" + date);
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        }

        return "booking/fragments :: time-slots";
    }

    /**
     * Returns availability only; it cannot read, create, or modify bookings.
     *
     * @param from first visitor-local date, inclusive
     * @param to last visitor-local date, inclusive
     * @param timezone visitor IANA timezone
     * @return available slots converted to the visitor timezone
     */
    @GetMapping("/api/availability")
    @ResponseBody
    public ResponseEntity<PublicAvailabilityResponse> getPublicAvailability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "America/Chicago") final String timezone) {

        try {
            return ResponseEntity.ok(publicAvailabilityService.availability(from, to, timezone));
        } catch (final DateTimeException | IllegalArgumentException e) {
            log.debug("Rejected public availability request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Creates a new booking.
     *
     * <p>Requests rejected by {@code @Valid} or JSON binding never reach this method.
     * {@link BookingRequestExceptionHandler} explains those failures.
     *
     * @param request Booking details
     * @return Created booking
     */
    @PostMapping("/book")
    @ResponseBody
    public ResponseEntity<?> createBooking(
            @Valid @RequestBody final CreateBookingRequest request, final HttpServletRequest httpRequest) {

        try {
            log.info("Creating booking for type {} at {}", request.bookingTypeId(), request.startTime());
            final var distinctId = analyticsDistinctId(httpRequest);
            distinctId.ifPresent(
                    id -> eventPublisher.publishEvent(new BookingSubmittedEvent(id, request.bookingTypeId())));

            final var booking = bookingService.createBooking(
                    request.bookingTypeId(),
                    request.attendeeName(),
                    request.attendeeEmail(),
                    request.attendeePhone(),
                    request.startTime(),
                    request.message(),
                    distinctId.orElse(null));

            log.info("Successfully created booking with confirmation code: {}", booking.confirmationCode());
            return ResponseEntity.ok(booking);

        } catch (final SlotNotAvailableException exception) {
            return bookingFailure(exception, HttpStatus.CONFLICT, exception.getMessage());
        } catch (final InvalidBookingException | BookingTypeNotFoundException exception) {
            return bookingFailure(exception, HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (final Exception exception) {
            return bookingFailure(
                    exception, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create booking. Please try again.");
        }
    }

    private ResponseEntity<BookingErrorResponse> bookingFailure(
            final Exception exception, final HttpStatus status, final String message) {
        log.error("Failed to create booking", exception);
        try {
            eventPublisher.publishEvent(ServerExceptionReportedEvent.from("booking.create", exception));
        } catch (final RuntimeException reportingFailure) {
            log.warn("Failed to report booking exception", reportingFailure);
        }
        return ResponseEntity.status(status).body(new BookingErrorResponse(message));
    }

    /**
     * View booking confirmation page.
     *
     * @param confirmationCode Booking confirmation code
     * @param model Spring MVC model
     * @return Thymeleaf template name
     */
    @GetMapping("/confirmation/{confirmationCode}")
    public String viewBooking(@PathVariable final String confirmationCode, final Model model) {
        try {
            final var booking = bookingService.getBookingByConfirmationCode(confirmationCode);
            model.addAttribute("booking", booking);
            return "booking/confirmation";
        } catch (final Exception e) {
            log.error("Booking not found: {}", confirmationCode, e);
            model.addAttribute("error", "Booking not found");
            return "booking/not-found";
        }
    }

    /**
     * Cancel a booking.
     *
     * @param confirmationCode Booking confirmation code
     * @return Success response
     */
    @PostMapping("/confirmation/{confirmationCode}/cancel")
    @ResponseBody
    public ResponseEntity<Void> cancelBooking(
            @PathVariable final String confirmationCode,
            final HttpServletRequest request,
            final HttpServletResponse response) {
        try {
            log.info("Cancelling booking: {}", confirmationCode);
            final var booking = bookingService.getBookingByConfirmationCode(confirmationCode);
            bookingService.cancelBooking(
                    booking.id(), analyticsDistinctId(request).orElse(null));
            response.setHeader("HX-Redirect", "/booking/confirmation/" + confirmationCode);
            return ResponseEntity.ok().build();
        } catch (final Exception e) {
            log.error("Failed to cancel booking: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    private void publishAvailabilityViewed(
            final HttpServletRequest request, final Long bookingTypeId, final LocalDate date, final int slotCount) {
        final var distinctId = analyticsDistinctId(request);
        if (distinctId.isEmpty()) {
            return;
        }

        final var session = request.getSession(true);
        final var attribute = "booking-availability-" + bookingTypeId + "-" + date;
        if (session.getAttribute(attribute) != null) {
            return;
        }
        session.setAttribute(attribute, Boolean.TRUE);
        eventPublisher.publishEvent(new BookingAvailabilityViewedEvent(distinctId.get(), bookingTypeId, slotCount));
    }

    private Optional<String> analyticsDistinctId(final HttpServletRequest request) {
        if (automatedProbeDetector.isAutomatedProbe(request)) {
            return Optional.empty();
        }
        return visitorDistinctIdResolver.resolve(request);
    }
}
