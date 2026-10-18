package com.learnhub.assistant.chat.api;

import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.assistant.chat.stream.ChatStreamService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(prefix = "learnhub.assistant", name = "chat-enabled", havingValue = "true")
@RequestMapping("/api/assistant")
public class ChatController {
    private final ChatConversationService conversations;
    private final ChatPersistenceService persistence;
    private final ChatHistoryService history;
    private final AssistantExecutionManager executions;
    private final AssistantSessionVerifier sessions;
    private final ChatStreamService streams;

    public ChatController(ChatConversationService conversations, ChatPersistenceService persistence,
                          ChatHistoryService history, AssistantExecutionManager executions,
                          AssistantSessionVerifier sessions, ChatStreamService streams) {
        this.conversations = conversations; this.persistence = persistence; this.history = history;
        this.executions = executions; this.sessions = sessions;
        this.streams = streams;
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

    @PostMapping(value = "/conversations/{id}/runs", produces = "text/event-stream")
    public SseEmitter send(@AuthenticationPrincipal LearnHubPrincipal user,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable String id,
            @Valid @RequestBody Send body, jakarta.servlet.http.HttpServletRequest servletRequest,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = streams.open(user.userId(), id, body.clientRequestId(), body.message(),
                sessions.checkpoint(user.userId(), authorization));
        servletRequest.setAttribute(com.learnhub.config.SecurityConfig.ASSISTANT_SSE_ATTRIBUTE, true);
        return emitter;
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
