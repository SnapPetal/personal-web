package biz.thonbecker.personal.booking.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import biz.thonbecker.personal.booking.api.BookingType;
import biz.thonbecker.personal.booking.api.TimeSlot;
import biz.thonbecker.personal.booking.platform.BookingService;
import biz.thonbecker.personal.booking.platform.web.model.BookingChatSlotButton;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class BookingChatControllerTest {

    private static final String MONDAY_START = "2026-10-05T15:00:00-05:00";
    private static final String MONDAY_END = "2026-10-05T15:30:00-05:00";

    private BookingService bookingService;
    private ChatModel chatModel;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bookingService = Mockito.mock(BookingService.class);
        chatModel = Mockito.mock(ChatModel.class);
        final var properties = new BookingChatProperties();
        final var service = new BookingChatService(
                new PublicAvailabilityService(bookingService),
                new BookingChatAnswerParser(new ObjectMapper()),
                properties,
                Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC),
                chatModel);
        final var controller = new BookingChatController(
                service, new BookingChatRateLimiter(properties), new BookingChatOriginGuard(properties), properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(bookingService.getActiveBookingTypes()).thenReturn(List.of(conversation()));
        when(bookingService.getAvailableSlots(any(), any())).thenReturn(List.of());
    }

    @Test
    void returnsOnlySlotsSuppliedByAvailability() throws Exception {
        when(bookingService.getAvailableSlots(any(), any(LocalDate.class))).thenAnswer(invocation -> {
            final LocalDate date = invocation.getArgument(1);
            if (!LocalDate.of(2026, 10, 5).equals(date)) {
                return List.of();
            }
            return List.of(
                    new TimeSlot(
                            null, LocalDateTime.of(2026, 10, 5, 15, 0), LocalDateTime.of(2026, 10, 5, 15, 30), true),
                    new TimeSlot(
                            null, LocalDateTime.of(2026, 10, 5, 16, 0), LocalDateTime.of(2026, 10, 5, 16, 30), true));
        });
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                        {
                          "message": "I have an opening Monday at 3:00 PM.",
                          "action": "show_availability",
                          "availableSlots": [
                            {
                              "start": "2026-10-05T15:00:00-05:00",
                              "end": "2026-10-05T15:30:00-05:00",
                              "timezone": "America/Chicago"
                            },
                            {
                              "start": "2026-10-06T15:00:00-05:00",
                              "end": "2026-10-06T15:30:00-05:00",
                              "timezone": "America/Chicago"
                            }
                          ]
                        }
                        """));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://booking.thonbecker.biz")
                        .header("CF-Connecting-IP", "test-availability")
                        .content("""
                                {"message":"What is available Monday?","timezone":"America/Chicago"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("I have an opening Monday at 3:00 PM."))
                .andExpect(jsonPath("$.action").value("show_availability"))
                .andExpect(jsonPath("$.availableSlots.length()").value(1))
                .andExpect(jsonPath("$.availableSlots[0].start").value(MONDAY_START))
                .andExpect(jsonPath("$.availableSlots[0].end").value(MONDAY_END))
                .andExpect(jsonPath("$.availableSlots[0].timezone").value("America/Chicago"));

        final var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        final var promptText = captor.getValue().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertThat(promptText).contains(MONDAY_START).contains("What is available Monday?");
        assertThat(promptText).doesNotContain("2026-10-06");
        assertThat(captor.getValue().getOptions()).isInstanceOf(BedrockChatOptions.class);
        assertThat(((BedrockChatOptions) captor.getValue().getOptions()).getModel())
                .isEqualTo("us.anthropic.claude-3-5-haiku-20241022-v1:0");
        verify(bookingService).getActiveBookingTypes();
    }

    @Test
    void rendersGroundedSlotsOnTheBookingPage() throws Exception {
        when(bookingService.getAvailableSlots(any(), any(LocalDate.class))).thenAnswer(invocation -> {
            final LocalDate date = invocation.getArgument(1);
            if (!LocalDate.of(2026, 10, 5).equals(date)) {
                return List.of();
            }
            return List.of(new TimeSlot(
                    null, LocalDateTime.of(2026, 10, 5, 15, 0), LocalDateTime.of(2026, 10, 5, 15, 30), true));
        });
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                        {"message":"I have an opening Monday at 3:00 PM.","action":"show_availability","availableSlots":[{"start":"2026-10-05T15:00:00-05:00","end":"2026-10-05T15:30:00-05:00","timezone":"America/Chicago"},{"start":"2026-10-06T15:00:00-05:00","end":"2026-10-06T15:30:00-05:00","timezone":"America/Chicago"}]}
                        """));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("HX-Request", "true")
                        .header("Origin", "https://booking.thonbecker.biz")
                        .header("CF-Connecting-IP", "test-htmx")
                        .param("message", "What is available Monday?")
                        .param("timezone", "America/Chicago"))
                .andExpect(status().isOk())
                .andExpect(view().name("booking/fragments :: chat-reply"))
                .andExpect(model().attributeDoesNotExist("chatError"))
                .andExpect(model().attribute(
                                "chatSlots",
                                List.of(new BookingChatSlotButton(MONDAY_START, "Mon, Oct 5, 3:00 PM CDT"))));
    }

    @Test
    void answersWithNoSlotsWithoutCallingTheModel() throws Exception {
        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://booking.thonbecker.biz")
                        .header("CF-Connecting-IP", "test-no-slots")
                        .content("{\"message\":\"What is available?\",\"timezone\":\"America/Chicago\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(BookingChatService.NO_OPENINGS))
                .andExpect(jsonPath("$.action").value("show_availability"))
                .andExpect(jsonPath("$.availableSlots.length()").value(0));

        verify(chatModel, never()).call(any(Prompt.class));
        verify(bookingService).getActiveBookingTypes();
    }

    @Test
    void rejectsRequestsFromOriginsOtherThanThePublicBookingSites() throws Exception {
        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://attacker.example")
                        .header("CF-Connecting-IP", "test-origin")
                        .content("{\"message\":\"What is available?\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Origin not allowed"));

        verifyNoInteractions(bookingService, chatModel);
    }

    @Test
    void doesNotExposeMalformedSlotsReturnedByTheModel() throws Exception {
        when(bookingService.getAvailableSlots(any(), any(LocalDate.class)))
                .thenReturn(List.of(new TimeSlot(
                        null, LocalDateTime.of(2026, 10, 5, 15, 0), LocalDateTime.of(2026, 10, 5, 15, 30), true)));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                        {
                          "response": {
                            "message": "Here are the times:",
                            "action": "show_availability",
                            "availableSlots": [
                              { "start": "not-a-date" },
                              { "start": "2026-10-05T15:00:00-05:00", "end": 123 }
                            ]
                          }
                        }
                        """));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-malformed-slots")
                        .content("{\"message\":\"What is available?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Here are the times"))
                .andExpect(jsonPath("$.action").value("show_availability"))
                .andExpect(jsonPath("$.availableSlots.length()").value(0));
    }

    @Test
    void rejectsBadInput() throws Exception {
        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-empty")
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message is required and must be at most 1000 characters"));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-long")
                        .content("{\"message\":\"" + "a".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message is required and must be at most 1000 characters"));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-zone")
                        .content("{\"message\":\"What is available?\",\"timezone\":\"Mars/Phobos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("timezone must be a valid IANA timezone"));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body must be JSON"));

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("HX-Request", "true")
                        .header("CF-Connecting-IP", "test-form")
                        .param("message", " "))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("booking/fragments :: chat-reply"))
                .andExpect(model().attribute("chatError", "message is required and must be at most 1000 characters"));

        verifyNoInteractions(chatModel);
        verify(bookingService, never()).getActiveBookingTypes();
    }

    @Test
    void limitsRepeatedRequestsFromTheSameAddress() throws Exception {
        for (var attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(post("/booking/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("CF-Connecting-IP", "test-rate")
                            .content("{\"message\":\" \"}"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/booking/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("CF-Connecting-IP", "test-rate")
                        .content("{\"message\":\"What is available?\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Please try again later"));

        verifyNoInteractions(chatModel, bookingService);
    }

    private static BookingType conversation() {
        return new BookingType(1L, "Conversation", "A conversation", 30, 0, true, "#1f3f77");
    }

    private static ChatResponse chatResponse(final String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
