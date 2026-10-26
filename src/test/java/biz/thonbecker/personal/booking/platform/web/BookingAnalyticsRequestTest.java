package biz.thonbecker.personal.booking.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.booking.api.Booking;
import biz.thonbecker.personal.booking.api.BookingAvailabilityViewedEvent;
import biz.thonbecker.personal.booking.api.BookingStartedEvent;
import biz.thonbecker.personal.booking.api.BookingStatus;
import biz.thonbecker.personal.booking.api.BookingSubmittedEvent;
import biz.thonbecker.personal.booking.api.BookingType;
import biz.thonbecker.personal.booking.domain.exceptions.BookingTypeNotFoundException;
import biz.thonbecker.personal.booking.domain.exceptions.InvalidBookingException;
import biz.thonbecker.personal.booking.domain.exceptions.SlotNotAvailableException;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.booking.platform.web.model.BookingErrorResponse;
import biz.thonbecker.personal.booking.platform.web.model.CreateBookingRequest;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import jakarta.servlet.http.Cookie;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

class BookingAnalyticsRequestTest {

    private BookingService bookingService;
    private ApplicationEventPublisher eventPublisher;
    private BookingStartDeduper bookingStartDeduper;
    private BookingController controller;

    @BeforeEach
    void setUp() {
        bookingService = Mockito.mock(BookingService.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        bookingStartDeduper = new BookingStartDeduper();
        controller = new BookingController(
                bookingService,
                new PublicAvailabilityService(bookingService),
                eventPublisher,
                new VisitorDistinctIdResolver(),
                new AutomatedProbeDetector(),
                bookingStartDeduper);
        when(bookingService.getActiveBookingTypes()).thenReturn(List.of());
        when(bookingService.getAvailableSlots(any(), any())).thenReturn(List.of());
    }

    @Test
    void pageRenderDoesNotRecordBookingStarted() {
        final var view = controller.bookingPage(new ExtendedModelMap());

        assertThat(view).isEqualTo("booking/index");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void monitorPageRenderDoesNotRecordBookingStarted() {
        final var view = controller.bookingPage(new ExtendedModelMap());

        assertThat(view).isEqualTo("booking/index");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void choosingAMeetingTypeRecordsOneStartForTheVisitor() {
        final var request = browserRequest("POST", "/booking/started");
        request.setCookies(postHogCookie("visitor-1"));

        controller.bookingStarted(request);
        controller.bookingStarted(request);

        verify(eventPublisher, times(1)).publishEvent(new BookingStartedEvent("visitor-1"));
    }

    @Test
    void headerDistinctIdIsUsedWhenPresent() {
        final var request = browserRequest("POST", "/booking/started");
        request.addHeader(VisitorDistinctIdResolver.HEADER_NAME, "header-visitor");
        request.setCookies(postHogCookie("cookie-visitor"));

        controller.bookingStarted(request);

        verify(eventPublisher).publishEvent(new BookingStartedEvent("header-visitor"));
    }

    @Test
    void sharedAnonymousIdIsNotRecorded() {
        final var request = browserRequest("POST", "/booking/started");
        request.addHeader(VisitorDistinctIdResolver.HEADER_NAME, VisitorDistinctIdResolver.SHARED_ANONYMOUS_ID);

        controller.bookingStarted(request);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void startWithoutAVisitorIdIsSkipped() {
        controller.bookingStarted(browserRequest("POST", "/booking/started"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void uptimeMonitorDoesNotRecordAStart() {
        final var request = browserRequest("POST", "/booking/started");
        request.removeHeader("User-Agent");
        request.addHeader("User-Agent", "upptime.js.org");
        request.setCookies(postHogCookie("visitor-1"));

        controller.bookingStarted(request);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void blankUserAgentDoesNotRecordAStart() {
        final var request = browserRequest("POST", "/booking/started");
        request.removeHeader("User-Agent");
        request.setCookies(postHogCookie("visitor-1"));

        controller.bookingStarted(request);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void htmxPartialDoesNotRecordAStart() {
        final var request = browserRequest("POST", "/booking/started");
        request.addHeader("HX-Request", "true");
        request.setCookies(postHogCookie("visitor-1"));

        controller.bookingStarted(request);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void repeatedDistinctIdInsideTheCooldownIsIgnored() {
        final var first = Instant.parse("2026-10-14T00:00:00Z");

        assertThat(bookingStartDeduper.begin("visitor-1", first)).isTrue();
        assertThat(bookingStartDeduper.begin("visitor-1", first.plusSeconds(5))).isFalse();
        assertThat(bookingStartDeduper.begin("visitor-1", first.plus(BookingStartDeduper.DISTINCT_ID_COOLDOWN)))
                .isTrue();
    }

    @Test
    void startFloodPastTheMinuteCapIsIgnored() {
        final var now = Instant.parse("2026-10-14T00:00:00Z");

        for (var count = 0; count < BookingStartDeduper.MAX_STARTS_PER_MINUTE; count++) {
            assertThat(bookingStartDeduper.begin("visitor-" + count, now)).isTrue();
        }

        assertThat(bookingStartDeduper.begin("visitor-overflow", now.plusSeconds(1)))
                .isFalse();
    }

    @Test
    void availabilityViewUsesTheVisitorAndIgnoresARepeatForTheSameDay() {
        final var request = browserRequest("GET", "/booking/types/3/slots");
        request.setCookies(postHogCookie("visitor-1"));
        final var date = LocalDate.of(2026, 10, 20);

        controller.getAvailableSlots(3L, date, new ExtendedModelMap(), request, new MockHttpServletResponse());
        controller.getAvailableSlots(3L, date, new ExtendedModelMap(), request, new MockHttpServletResponse());

        verify(eventPublisher, times(1)).publishEvent(new BookingAvailabilityViewedEvent("visitor-1", 3L, 0));
    }

    @Test
    void availabilityViewFromAMonitorIsNotRecorded() {
        final var request = browserRequest("GET", "/booking/types/3/slots");
        request.removeHeader("User-Agent");
        request.addHeader("User-Agent", "ELB-HealthChecker/2.0");
        request.setCookies(postHogCookie("visitor-1"));

        final var view = controller.getAvailableSlots(
                3L, LocalDate.of(2026, 10, 20), new ExtendedModelMap(), request, new MockHttpServletResponse());

        assertThat(view).contains("time-slots");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void submittingABookingUsesTheVisitorDistinctId() {
        final var request = browserRequest("POST", "/booking/book");
        request.addHeader(VisitorDistinctIdResolver.HEADER_NAME, "visitor-9");
        final var start = LocalDateTime.of(2026, 10, 20, 11, 0);
        when(bookingService.createBooking(
                        eq(3L), eq("Ada"), eq("ada@example.com"), isNull(), eq(start), eq("hello"), eq("visitor-9")))
                .thenReturn(sampleBooking(start));

        controller.createBooking(new CreateBookingRequest(3L, "Ada", "ada@example.com", null, start, "hello"), request);

        verify(eventPublisher).publishEvent(new BookingSubmittedEvent("visitor-9", 3L));
        verify(bookingService).createBooking(3L, "Ada", "ada@example.com", null, start, "hello", "visitor-9");
    }

    @Test
    void unavailableSlotIsAConflictWithTheServerMessage() {
        final var response =
                failedCreate(new SlotNotAvailableException("No availability slot exists for the selected time"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .isEqualTo(new BookingErrorResponse("No availability slot exists for the selected time"));
        verifyReportedException("No availability slot exists for the selected time");
    }

    @Test
    void invalidBookingIsABadRequestWithTheServerMessage() {
        final var response = failedCreate(new InvalidBookingException("Cannot book time slots in the past"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new BookingErrorResponse("Cannot book time slots in the past"));
    }

    @Test
    void missingBookingTypeIsABadRequestWithTheServerMessage() {
        final var response = failedCreate(new BookingTypeNotFoundException(3L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new BookingErrorResponse("Booking type not found: 3"));
    }

    @Test
    void unexpectedCreateFailureIsAGenericServerError() {
        final var response = failedCreate(new IllegalStateException("token attendee@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .isEqualTo(new BookingErrorResponse("Failed to create booking. Please try again."));
        verify(eventPublisher)
                .publishEvent(argThat((Object event) -> event instanceof ServerExceptionReportedEvent reported
                        && "booking.create".equals(reported.source())
                        && reported.message().contains("[redacted-email]")
                        && !reported.message().contains("attendee@example.com")));
    }

    private ResponseEntity<?> failedCreate(final RuntimeException failure) {
        final var request = browserRequest("POST", "/booking/book");
        request.addHeader(VisitorDistinctIdResolver.HEADER_NAME, "visitor-9");
        final var start = LocalDateTime.of(2026, 10, 21, 11, 0);
        when(bookingService.createBooking(
                        eq(3L), eq("Ada"), eq("ada@example.com"), isNull(), eq(start), eq("hello"), eq("visitor-9")))
                .thenThrow(failure);

        return controller.createBooking(
                new CreateBookingRequest(3L, "Ada", "ada@example.com", null, start, "hello"), request);
    }

    private void verifyReportedException(final String message) {
        verify(eventPublisher)
                .publishEvent(argThat((Object event) -> event instanceof ServerExceptionReportedEvent reported
                        && "booking.create".equals(reported.source())
                        && message.equals(reported.message())));
    }

    private MockHttpServletRequest browserRequest(final String method, final String path) {
        final var request = new MockHttpServletRequest(method, path);
        request.addHeader("User-Agent", "Mozilla/5.0");
        return request;
    }

    private Cookie postHogCookie(final String distinctId) {
        final var json = "{\"distinct_id\":\"" + distinctId + "\"}";
        return new Cookie("ph_phc_test_posthog", URLEncoder.encode(json, StandardCharsets.UTF_8));
    }

    private Booking sampleBooking(final LocalDateTime start) {
        return new Booking(
                8L,
                "CONFIRM1",
                new BookingType(3L, "Chat", "Talk", 30, 0, true, "#000000"),
                "Ada",
                "ada@example.com",
                null,
                start,
                start.plusMinutes(30),
                "hello",
                BookingStatus.CONFIRMED,
                Instant.parse("2026-10-14T00:00:00Z"));
    }
}
