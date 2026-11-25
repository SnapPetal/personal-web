package biz.thonbecker.personal.budget.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import biz.thonbecker.personal.budget.platform.BudgetChatService;
import biz.thonbecker.personal.budget.platform.document.BudgetDocumentExtractor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BudgetChatControllerTest {

    private BudgetChatService chatService;
    private BudgetDocumentExtractor documentExtractor;
    private Authentication authentication;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = Mockito.mock(BudgetChatService.class);
        documentExtractor = Mockito.mock(BudgetDocumentExtractor.class);
        authentication = Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user-42");
        mockMvc = MockMvcBuilders.standaloneSetup(new BudgetChatController(chatService, documentExtractor))
                .build();
    }

    @Test
    void sendsQuestionAndExtractedAttachmentsToTheChatService() throws Exception {
        final var file = new MockMultipartFile("files", "budget.csv", "text/csv", "category,amount".getBytes());
        final var document = new BudgetDocumentExtractor.BudgetDocument("budget.csv", "category,amount");
        final var conversationId = UUID.fromString("6a7ef17c-3a4b-4d30-9d87-c632ee243596");
        when(documentExtractor.extract(any())).thenReturn(List.of(document));
        when(chatService.answer(
                        eq("user-42"), eq(conversationId), eq("How can I balance this?"), eq(List.of(document))))
                .thenReturn("Assign every dollar to a category.");

        mockMvc.perform(multipart("/budget/chat")
                        .file(file)
                        .param("message", "  How can I balance this?  ")
                        .param("conversationId", conversationId.toString())
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Assign every dollar to a category."))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(documentExtractor).extract(any());
        verify(chatService).answer("user-42", conversationId, "How can I balance this?", List.of(document));
    }

    @Test
    void rejectsEmptyOrOverlongQuestionsBeforeCallingTheModel() throws Exception {
        mockMvc.perform(multipart("/budget/chat").param("message", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter a question of 2,000 characters or fewer."));

        mockMvc.perform(multipart("/budget/chat").param("message", "x".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter a question of 2,000 characters or fewer."));

        mockMvc.perform(multipart("/budget/chat")
                        .param("message", "Question")
                        .param("conversationId", "bad-session-id")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Start a new conversation and try again."));

        Mockito.verifyNoInteractions(chatService, documentExtractor);
    }

    @Test
    void loadsConversationUsingTheAuthenticatedUserAndClearsItOnRequest() throws Exception {
        final var conversationId = UUID.fromString("6a7ef17c-3a4b-4d30-9d87-c632ee243596");
        when(chatService.conversation("user-42", conversationId))
                .thenReturn(List.of(new BudgetChatService.ConversationMessage("user", "How do I start?")));

        mockMvc.perform(get("/budget/conversation")
                        .param("conversationId", conversationId.toString())
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("How do I start?"));

        mockMvc.perform(post("/budget/conversation/reset")
                        .param("conversationId", conversationId.toString())
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isString())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(chatService).conversation("user-42", conversationId);
        verify(chatService).clearConversation("user-42", conversationId);
    }
}
