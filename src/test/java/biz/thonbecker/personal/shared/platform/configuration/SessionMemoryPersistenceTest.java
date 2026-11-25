package biz.thonbecker.personal.shared.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import biz.thonbecker.personal.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SessionMemoryPersistenceTest {

    @Autowired
    private SessionService sessionService;

    @Test
    void persistsConversationMessagesInLiquibaseManagedTables() {
        final var sessionId = "test-" + UUID.randomUUID();

        try {
            sessionService.create(CreateSessionRequest.builder().id(sessionId).build());
            sessionService.appendMessage(sessionId, new UserMessage("Remember this preference."));
            sessionService.appendMessage(sessionId, new AssistantMessage("I will remember it."));

            assertThat(sessionService.getActiveMessages(sessionId))
                    .extracting(message -> message.getText())
                    .containsExactly("Remember this preference.", "I will remember it.");
        } finally {
            sessionService.delete(sessionId);
        }
    }

    @Test
    void deletesExpiredSessionsAndTheirMessages() {
        final var sessionId = "expired-test-" + UUID.randomUUID();
        sessionService.create(CreateSessionRequest.builder()
                .id(sessionId)
                .timeToLive(Duration.ofSeconds(-1))
                .build());
        sessionService.appendMessage(sessionId, new UserMessage("This message should expire."));

        assertThat(sessionService.deleteExpiredSessions(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(sessionService.findById(sessionId)).isNull();
    }

    @Test
    void assignsConfiguredShortTermLifetimeToNewSessions() {
        final var session = sessionService.create(CreateSessionRequest.builder()
                .id("ttl-test-" + UUID.randomUUID())
                .build());

        try {
            assertThat(session.expiresAt())
                    .isBetween(
                            Instant.now().plus(Duration.ofDays(14)).minusSeconds(5),
                            Instant.now().plus(Duration.ofDays(14)).plusSeconds(5));
        } finally {
            sessionService.delete(session.id());
        }
    }
}
