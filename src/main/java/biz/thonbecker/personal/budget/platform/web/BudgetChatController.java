package biz.thonbecker.personal.budget.platform.web;

import biz.thonbecker.personal.budget.platform.BudgetChatService;
import biz.thonbecker.personal.budget.platform.BudgetChatService.ConversationMessage;
import biz.thonbecker.personal.budget.platform.document.BudgetDocumentException;
import biz.thonbecker.personal.budget.platform.document.BudgetDocumentExtractor;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

@Controller
@Slf4j
class BudgetChatController {

    private static final int MAX_MESSAGE_CHARACTERS = 2_000;

    private final BudgetChatService chatService;
    private final BudgetDocumentExtractor documentExtractor;

    BudgetChatController(final BudgetChatService chatService, final BudgetDocumentExtractor documentExtractor) {
        this.chatService = chatService;
        this.documentExtractor = documentExtractor;
    }

    @PostMapping(value = "/budget/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    ResponseEntity<BudgetChatResponse> chat(
            @RequestParam(name = "message", required = false) final String message,
            @RequestParam(name = "conversationId", required = false) final String conversationId,
            @RequestParam(name = "files", required = false) final List<MultipartFile> files,
            final Authentication authentication) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_CHARACTERS) {
            return error(400, "Enter a question of 2,000 characters or fewer.");
        }
        final var parsedConversationId = parseConversationId(conversationId);
        if (parsedConversationId == null) {
            return error(400, "Start a new conversation and try again.");
        }

        try {
            final var documents = documentExtractor.extract(files);
            final var answer =
                    chatService.answer(authentication.getName(), parsedConversationId, message.trim(), documents);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BudgetChatResponse(answer));
        } catch (final BudgetDocumentException exception) {
            return error(400, exception.getMessage());
        } catch (final RuntimeException exception) {
            log.error("Budget chat request failed", exception);
            return error(503, "Budget Helper is temporarily unavailable. Please try again.");
        }
    }

    @GetMapping("/budget/conversation")
    @ResponseBody
    ResponseEntity<ConversationResponse> conversation(
            @RequestParam(name = "conversationId", required = false) final String conversationId,
            final Authentication authentication) {
        final var parsedConversationId = parseConversationId(conversationId);
        if (parsedConversationId == null) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ConversationResponse(List.of(), "Start a new conversation and try again."));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ConversationResponse(
                        chatService.conversation(authentication.getName(), parsedConversationId), null));
    }

    @PostMapping("/budget/conversation/reset")
    @ResponseBody
    ResponseEntity<ConversationResetResponse> resetConversation(
            @RequestParam(name = "conversationId", required = false) final String conversationId,
            final Authentication authentication) {
        final var parsedConversationId = parseConversationId(conversationId);
        if (parsedConversationId == null) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ConversationResetResponse(null, "Start a new conversation and try again."));
        }
        chatService.clearConversation(authentication.getName(), parsedConversationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ConversationResetResponse(UUID.randomUUID().toString(), null));
    }

    private static UUID parseConversationId(final String conversationId) {
        if (Objects.isNull(conversationId) || conversationId.length() > 36) {
            return null;
        }
        try {
            final var parsed = UUID.fromString(conversationId);
            return parsed.toString().equalsIgnoreCase(conversationId) ? parsed : null;
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    private static ResponseEntity<BudgetChatResponse> error(final int status, final String error) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new BudgetChatResponse(null, error));
    }

    record BudgetChatResponse(String answer, String error) {

        BudgetChatResponse(final String answer) {
            this(answer, null);
        }
    }

    record ConversationResponse(List<ConversationMessage> messages, String error) {}

    record ConversationResetResponse(String conversationId, String error) {}
}
