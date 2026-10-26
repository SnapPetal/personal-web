package biz.thonbecker.personal.booking.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import biz.thonbecker.personal.booking.api.Booking;
import biz.thonbecker.personal.booking.api.BookingStatus;
import biz.thonbecker.personal.booking.api.BookingType;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class CreateBookingPayloadTest {

    /**
     * JSON.stringify of the object built in booking/index.html submitBooking. startTime is the
     * data-start-time value Thymeleaf writes for an 11:00 slot ({@code 2026-10-22T11:00}, seconds
     * omitted). Phone and message are null when those inputs are blank. bookingTypeId is a JSON
     * number from parseInt.
     */
    private static final String PAGE_PAYLOAD = """
            {"bookingTypeId":1,"attendeeName":"Ada Lovelace","attendeeEmail":"ada@example.com","attendeePhone":null,"startTime":"2026-10-22T11:00","message":null}\
            """;

    private BookingService bookingService;
    private ApplicationEventPublisher eventPublisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bookingService = Mockito.mock(BookingService.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        final var controller = new BookingController(
                bookingService,
                new PublicAvailabilityService(bookingService),
                eventPublisher,
                new VisitorDistinctIdResolver(),
                new AutomatedProbeDetector(),
                new BookingStartDeduper());
        final var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BookingRequestExceptionHandler(eventPublisher))
                .setValidator(validator)
                .build();
    }

    @Test
    void pagePayloadCreatesTheBooking() throws Exception {
        when(bookingService.createBooking(
                        any(),
                        any(),
                        any(),
                        nullable(String.class),
                        any(),
                        nullable(String.class),
                        nullable(String.class)))
                .thenReturn(sampleBooking());

        mockMvc.perform(post("/booking/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAGE_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationCode").value("ABC123"));

        verify(bookingService)
                .createBooking(
                        eq(1L),
                        eq("Ada Lovelace"),
                        eq("ada@example.com"),
                        isNull(),
                        eq(LocalDateTime.of(2026, 10, 22, 11, 0)),
                        isNull(),
                        isNull());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void invalidEmailNamesTheFieldAndReportsTheException() throws Exception {
        final var payload = PAGE_PAYLOAD.replace("ada@example.com", "not-an-email");

        mockMvc.perform(post("/booking/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("attendeeEmail")));

        verify(bookingService, never())
                .createBooking(
                        any(),
                        any(),
                        any(),
                        nullable(String.class),
                        any(),
                        nullable(String.class),
                        nullable(String.class));
        verify(eventPublisher)
                .publishEvent(argThat((Object event) -> event instanceof ServerExceptionReportedEvent reported
                        && reported.exceptionType().endsWith("MethodArgumentNotValidException")
                        && "booking.create".equals(reported.source())));
    }

    @Test
    void unreadableStartTimeNamesTheFieldAndReportsTheException() throws Exception {
        final var payload = PAGE_PAYLOAD.replace("2026-10-22T11:00", "11:00 AM");

        mockMvc.perform(post("/booking/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("startTime")));

        verify(bookingService, never())
                .createBooking(
                        any(),
                        any(),
                        any(),
                        nullable(String.class),
                        any(),
                        nullable(String.class),
                        nullable(String.class));
        verify(eventPublisher)
                .publishEvent(argThat((Object event) -> event instanceof ServerExceptionReportedEvent reported
                        && reported.exceptionType().endsWith("HttpMessageNotReadableException")
                        && "booking.create".equals(reported.source())));
    }

    private static Booking sampleBooking() {
        return new Booking(
                9L,
                "ABC123",
                new BookingType(1L, "Intro", "A short intro", 45, 0, true, "#336699"),
                "Ada Lovelace",
                "ada@example.com",
                null,
                LocalDateTime.of(2026, 10, 22, 11, 0),
                LocalDateTime.of(2026, 10, 22, 11, 45),
                null,
                BookingStatus.CONFIRMED,
                Instant.parse("2026-10-21T08:40:00Z"));
    }
}
