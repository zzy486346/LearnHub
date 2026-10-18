package com.learnhub.assistant.chat.api;

import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@ConditionalOnProperty(prefix = "learnhub.assistant", name = "chat-enabled", havingValue = "true")
@RequestMapping("/api/assistant")
public class ChatController {
    private final ChatConversationService conversations;
    private final ChatPersistenceService persistence;
    private final ChatHistoryService history;
    private final AssistantExecutionManager executions;
    private final AssistantSessionVerifier sessions;
    private final AssistantLimits limits;

    public ChatController(ChatConversationService conversations, ChatPersistenceService persistence,
                          ChatHistoryService history, AssistantExecutionManager executions,
                          AssistantSessionVerifier sessions, AssistantLimits limits) {
        this.conversations = conversations; this.persistence = persistence; this.history = history;
        this.executions = executions; this.sessions = sessions; this.limits = limits;
    }

    @PostMapping("/conversations")
    public ApiResponse<ChatPersistenceService.Conversation> create(@AuthenticationPrincipal LearnHubPrincipal user,
                                                                  @Valid @RequestBody Create body) {
        return ApiResponse.success(conversations.create(user.userId(), body.courseId(), body.title()));
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ChatPersistenceService.Conversation>> list(@AuthenticationPrincipal LearnHubPrincipal user,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(conversations.list(user.userId(), offset, limit));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<ChatHistoryService.HistoryMessage>> messages(@AuthenticationPrincipal LearnHubPrincipal user,
            @PathVariable String id, @RequestParam(defaultValue = "9223372036854775807") long beforeSeq,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(history.messages(user.userId(), id, beforeSeq, limit));
    }

    @PatchMapping("/conversations/{id}")
    public ApiResponse<Void> rename(@AuthenticationPrincipal LearnHubPrincipal user, @PathVariable String id,
                                    @Valid @RequestBody Rename body) {
        history.require(user.userId(), id);
        persistence.rename(user.userId(), id, body.title());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal LearnHubPrincipal user, @PathVariable String id) {
        RuntimeException firstFailure = null;
        for (String runId : persistence.delete(user.userId(), id)) {
            try { executions.cancel(user.userId(), runId); }
            catch (RuntimeException failure) { if (firstFailure == null) firstFailure = failure; }
        }
        if (firstFailure != null) throw firstFailure;
        return ApiResponse.success(null);
    }

    @PostMapping("/conversations/{id}/runs")
    public DeferredResult<ResponseEntity<?>> send(@AuthenticationPrincipal LearnHubPrincipal user,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable String id,
            @Valid @RequestBody Send body) {
        var identity = sessions.checkpoint(user.userId(), authorization);
        identity.run();
        var started = conversations.start(user.userId(), id, body.clientRequestId(), body.message(), limits.getTimeout());
        if (!started.created()) {
            var response = new DeferredResult<ResponseEntity<?>>();
            var stored = started.run();
            response.setResult(ResponseEntity.ok(ApiResponse.success(new RunStatus(stored.id(), stored.conversationId(), stored.status()))));
            return response;
        }
        try {
            Runnable checkpoint = () -> {
                identity.run();
                String state = persistence.run(user.userId(), started.run().id()).status();
                if (!"RUNNING".equals(state)) throw new com.learnhub.common.exception.BusinessException(
                        "ASSISTANT_CANCELLED", "本次会话运行已终止");
            };
            var response = executions.submitCommitting(user.userId(), started.run().id(), checkpoint,
                    run -> conversations.prepareFake(user.userId(), started.run(), run),
                    (run, prepared) -> {
                        var stored = conversations.commitFake(user.userId(), started.run(), prepared);
                        return new RunStatus(stored.id(), stored.conversationId(), stored.status());
                    });
            response.onCompletion(() -> persistence.fail(user.userId(), started.run().id(), "FAILED", "ASSISTANT_INTERRUPTED"));
            return response;
        } catch (RuntimeException failure) {
            persistence.fail(user.userId(), started.run().id(), "FAILED", "ASSISTANT_UNAVAILABLE");
            throw failure;
        }
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RunStatus> run(@AuthenticationPrincipal LearnHubPrincipal user, @PathVariable String id) {
        var run = persistence.run(user.userId(), id);
        history.require(user.userId(), run.conversationId());
        // 不将内部租约令牌和原始输入作为运行状态返回。
        return ApiResponse.success(new RunStatus(run.id(), run.conversationId(), run.status()));
    }

    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<RunStatus> cancel(@AuthenticationPrincipal LearnHubPrincipal user, @PathVariable String id) {
        var run = persistence.cancel(user.userId(), id);
        executions.cancel(user.userId(), id);
        return ApiResponse.success(new RunStatus(run.id(), run.conversationId(), run.status()));
    }

    public record Create(@Positive long courseId, @NotBlank @Size(max = 120) String title) { }
    public record Rename(@NotBlank @Size(max = 120) String title) { }
    public record Send(@NotBlank @Size(max = 500) String message,
                       @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                       @NotNull String clientRequestId) { }
    public record RunStatus(String id, String conversationId, String status) { }
}
