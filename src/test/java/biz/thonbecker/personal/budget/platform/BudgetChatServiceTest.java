package biz.thonbecker.personal.budget.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import biz.thonbecker.personal.budget.platform.document.BudgetDocumentExtractor.BudgetDocument;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

class BudgetChatServiceTest {

    @Test
    void includesUploadedTextOnlyInTheSystemContextAndScopesMemoryToTheUser() {
        final var builder = mock(ChatClient.Builder.class);
        final var chatClient = mock(ChatClient.class);
        final var prompt = mock(ChatClient.ChatClientRequestSpec.class);
        final var call = mock(ChatClient.CallResponseSpec.class);
        final var sessionService = mock(SessionService.class);
        when(builder.defaultAdvisors(org.mockito.ArgumentMatchers.<List<Advisor>>any()))
                .thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(prompt);
        when(prompt.system(anyString())).thenReturn(prompt);
        when(prompt.user(anyString())).thenReturn(prompt);
        final var advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), anyString())).thenReturn(advisorSpec);
        when(prompt.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenAnswer(invocation -> {
                    final Consumer<ChatClient.AdvisorSpec> consumer = invocation.getArgument(0);
                    consumer.accept(advisorSpec);
                    return prompt;
                });
        when(prompt.call()).thenReturn(call);
        when(call.content()).thenReturn("Your plan balances when income equals assigned expenses.");

        final var service = new BudgetChatService(builder, sessionService);
        final var conversationId = UUID.fromString("6a7ef17c-3a4b-4d30-9d87-c632ee243596");
        final var answer = service.answer(
                "user-42",
                conversationId,
                "How should I plan this month?",
                List.of(new BudgetDocument("budget.csv", "Income,4000\nRent,1200\nGroceries,500")));

        assertThat(answer).contains("plan balances");
        final var systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        final var userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(prompt).system(systemPromptCaptor.capture());
        verify(prompt).user(userPromptCaptor.capture());
        assertThat(systemPromptCaptor.getValue()).contains("Income,4000", "Rent,1200");
        assertThat(userPromptCaptor.getValue()).isEqualTo("How should I plan this month?");
        verify(advisorSpec).param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "user:user-42:budget:" + conversationId);
    }

    @Test
    void loadsAndDeletesOnlyTheAuthenticatedUsersConversation() {
        final var builder = mock(ChatClient.Builder.class);
        final var sessionService = mock(SessionService.class);
        final var chatClient = mock(ChatClient.class);
        when(builder.defaultAdvisors(org.mockito.ArgumentMatchers.<List<Advisor>>any()))
                .thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        final var service = new BudgetChatService(builder, sessionService);
        final var conversationId = UUID.fromString("6a7ef17c-3a4b-4d30-9d87-c632ee243596");
        final var sessionId = "user:alice:budget:" + conversationId;
        when(sessionService.findById(sessionId)).thenReturn(mock(Session.class));
        when(sessionService.getEvents(eq(sessionId), any(EventFilter.class)))
                .thenReturn(List.of(
                        SessionEvent.builder()
                                .id("event-1")
                                .sessionId(sessionId)
                                .message(new UserMessage("How do I plan groceries?"))
                                .build(),
                        SessionEvent.builder()
                                .id("event-2")
                                .sessionId(sessionId)
                                .message(new AssistantMessage("Start with your income and priorities."))
                                .build()));

        assertThat(service.conversation("alice", conversationId))
                .containsExactly(
                        new BudgetChatService.ConversationMessage("user", "How do I plan groceries?"),
                        new BudgetChatService.ConversationMessage(
                                "assistant", "Start with your income and priorities."));
        service.clearConversation("alice", conversationId);

        verify(sessionService).getEvents(eq(sessionId), any(EventFilter.class));
        verify(sessionService).delete(sessionId);
        verify(sessionService, org.mockito.Mockito.never()).findById("user:bob:budget:" + conversationId);
    }
}
