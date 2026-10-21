package biz.thonbecker.personal.booking.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BookingRequestRejectionTest {

    private BookingService bookingService;
    private ApplicationEventPublisher eventPublisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bookingService = Mockito.mock(BookingService.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        final var controller = new BookingController(
                bookingService,
                eventPublisher,
                new VisitorDistinctIdResolver(),
                new AutomatedProbeDetector(),
                new BookingStartDeduper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BookingRequestExceptionHandler(eventPublisher))
                .build();
    }

    @Test
    void invalidEmailExplainsTheFieldAndReportsItWithoutTheTypedValue() throws Exception {
        mockMvc.perform(book("{\"bookingTypeId\":1,\"attendeeName\":\"Ada\",\"attendeeEmail\":\"ada at work\","
                        + "\"attendeePhone\":null,\"startTime\":\"2026-10-21T11:00\",\"message\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter a valid email address."));

        final var reported = reportedException();
        assertThat(reported.source()).isEqualTo("booking.create");
        assertThat(reported.message()).contains("attendeeEmail (Email)");
        assertThat(reported.message()).doesNotContain("ada at work");
        verifyNoInteractions(bookingService);
    }

    @Test
    void missingNameExplainsTheField() throws Exception {
        mockMvc.perform(book("{\"bookingTypeId\":1,\"attendeeName\":\" \",\"attendeeEmail\":\"ada@example.com\","
                        + "\"startTime\":\"2026-10-21T11:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter your name."));

        assertThat(reportedException().message()).contains("attendeeName (NotBlank)");
    }

    @Test
    void unreadableStartTimeNamesTheFieldInTheReport() throws Exception {
        mockMvc.perform(book("{\"bookingTypeId\":1,\"attendeeName\":\"Ada\",\"attendeeEmail\":\"ada@example.com\","
                        + "\"startTime\":\"10/21/2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingRequestExceptionHandler.UNREADABLE_MESSAGE));

        final var reported = reportedException();
        assertThat(reported.message()).isEqualTo("Booking request could not be read: startTime");
        verifyNoInteractions(bookingService);
    }

    private RequestBuilder book(final String json) {
        return post("/booking/book")
                .header("User-Agent", "Mozilla/5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private ServerExceptionReportedEvent reportedException() {
        final var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(ServerExceptionReportedEvent.class::isInstance)
                .map(ServerExceptionReportedEvent.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
