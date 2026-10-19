package com.learnhub.assistant.chat.stream;

import com.learnhub.assistant.chat.api.ChatController.RunStatus;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.chat.model.LangChainChatGenerator;
import com.learnhub.assistant.chat.model.LangChainChatGenerator.PreparedAnswer;
import com.learnhub.assistant.runtime.*;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** POST SSE只负责传输；正式历史、预算和取消继续由原有业务组件负责。 */
@Service
public class ChatStreamService {
    private final ChatConversationService conversations;
    private final ChatPersistenceService persistence;
    private final ChatHistoryService history;
    private final AssistantExecutionManager executions;
    private final AssistantLimits limits;
    private final AiProperties properties;
    private final ObjectProvider<LangChainChatGenerator> models;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "assistant-stream-heartbeat"); thread.setDaemon(true); return thread;
    });

    public ChatStreamService(ChatConversationService conversations, ChatPersistenceService persistence,
            ChatHistoryService history, AssistantExecutionManager executions, AssistantLimits limits, AiProperties properties,
            ObjectProvider<LangChainChatGenerator> models) {
        this.conversations = conversations; this.persistence = persistence; this.history = history;
        this.executions = executions; this.limits = limits; this.properties = properties;
        this.models = models;
    }

    public SseEmitter open(long owner, String conversationId, String requestId, String message, Runnable identity) {
        identity.run();
        var started = conversations.start(owner, conversationId, requestId, message, limits.getTimeout());
        var stored = started.run();
        var reply = new Reply(owner, stored.id());
        reply.emitter.onCompletion(reply::disconnected);
        reply.emitter.onTimeout(reply::disconnected);
        reply.emitter.onError(error -> reply.disconnected());
        try {
        reply.send("accepted", Map.of("status", stored.status(), "replayed", !started.created(),
                "modelMode", properties.resolvedMode().name(), "conversationId", conversationId));
        reply.checkOpen();
        if (!started.created()) {
            // 同一个run只读恢复，不启动第二个任务；RUNNING时客户端应查状态，不能重放生成。
            if ("RUNNING".equals(stored.status())) reply.finish("status", Map.of("status", stored.status(), "replayed", true));
            else reply.finish("final", Map.of("status", stored.status(), "replayed", true));
            return reply.emitter;
        }
        reply.heartbeat(heartbeats.scheduleAtFixedRate(
                () -> reply.send("status", Map.of("stage", "running", "heartbeat", true)), 10, 10, TimeUnit.SECONDS));
        Runnable checkpoint = () -> {
            identity.run();
            if (!"RUNNING".equals(persistence.run(owner, stored.id()).status()))
                throw new BusinessException("ASSISTANT_CANCELLED", "本次会话运行已终止");
        };
            var result = executions.submitCommitting(owner, stored.id(), checkpoint, run -> {
                reply.send("status", Map.of("stage", "context"));
                if (properties.resolvedMode() != AiProperties.Mode.FAKE) {
                    var model = models.getIfAvailable();
                    if (model == null || properties.resolvedMode() != AiProperties.Mode.DEEPSEEK)
                        throw new BusinessException("AI_DISABLED", "真实流式模型尚未启用");
                    reply.send("status", Map.of("stage", "generating", "modelMode", "DEEPSEEK"));
                    return model.prepare(owner, stored, run, text -> reply.send("delta", Map.of("text", text)));
                }
                String prepared = conversations.prepareFake(owner, stored, run);
                reply.send("status", Map.of("stage", "generating", "modelMode", "FAKE"));
                for (int offset = 0; offset < prepared.length();) {
                    run.check();
                    history.require(owner, conversationId);
                    int end = prepared.offsetByCodePoints(offset,
                            Math.min(24, prepared.codePointCount(offset, prepared.length())));
                    reply.send("delta", Map.of("text", prepared.substring(offset, end)));
                    offset = end;
                    try { Thread.sleep(25); }
                    catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException("ASSISTANT_CANCELLED", "本次会话运行已取消");
                    }
                }
                return new PreparedAnswer(prepared, "[]", "{}", List.of(), "FAKE");
            }, (run, prepared) -> {
                reply.checkOpen();
                var completed = conversations.commitAnswer(owner, stored, prepared.text(), prepared.sourcesJson(), prepared.usageJson());
                return new CommittedReply(new RunStatus(completed.id(), completed.conversationId(), completed.status()),
                        prepared.text(), prepared.sourcesJson());
            });
            result.setResultHandler(value -> {
                if (value instanceof ResponseEntity<?> response && response.getBody() instanceof ApiResponse<?> body
                        && "OK".equals(body.code())) {
                    try {
                        identity.run();
                        if (!(body.data() instanceof CommittedReply committed))
                            throw new IllegalStateException("missing committed chat reply");
                        var sources = conversations.confirmedSources(owner, stored, committed.text(), committed.sourcesJson());
                        reply.send("sources", Map.of("sources", sources));
                        reply.finish("final", Map.of("status", "COMPLETED"));
                    } catch (RuntimeException unavailable) {
                        // 正式终态已提交；不把COMPLETED改成FAILED。前端清稿，历史接口照常复核后返回。
                        String code = unavailable instanceof BusinessException business
                                ? business.getCode() : "ASSISTANT_UNAVAILABLE";
                        reply.finish("error", Map.of("code", code, "message", "会话权限或来源无法确认，请刷新历史", "clearDraft", true));
                    }
                } else {
                    String code = "ASSISTANT_UNAVAILABLE";
                    String errorMessage = "学习助理暂时不可用";
                    if (value instanceof ResponseEntity<?> response && response.getBody() instanceof ApiResponse<?> body) {
                        code = body.code(); errorMessage = body.message();
                    }
                    boolean cancelled = "ASSISTANT_CANCELLED".equals(code);
                    try { persistence.fail(owner, stored.id(), cancelled ? "CANCELLED" : "FAILED", code); }
                    finally { reply.finish(cancelled ? "cancelled" : "error",
                            Map.of("code", code, "message", errorMessage, "clearDraft", true)); }
                }
            });
        } catch (RuntimeException failure) {
            if (started.created()) persistence.fail(owner, stored.id(), "FAILED", "ASSISTANT_UNAVAILABLE");
            reply.finish("error", Map.of("code", "ASSISTANT_UNAVAILABLE", "message", "学习助理暂时不可用", "clearDraft", true));
        }
        return reply.emitter;
    }

    @PreDestroy public void close() { heartbeats.shutdownNow(); }

    private record CommittedReply(RunStatus run, String text, String sourcesJson) { }

    private final class Reply {
        final long owner;
        final String runId;
        final SseEmitter emitter = new SseEmitter(limits.getTimeout().toMillis() + 5000);
        long sequence;
        boolean terminal;
        volatile ScheduledFuture<?> heartbeat;
        Reply(long owner, String runId) { this.owner = owner; this.runId = runId; }

        void send(String event, Map<String, ?> payload) {
            boolean failed = false;
            synchronized (this) {
                if (terminal) return;
                try { write(event, payload); }
                catch (IOException | IllegalStateException disconnected) { failed = true; }
            }
            if (failed) disconnected();
        }
        synchronized void heartbeat(ScheduledFuture<?> task) {
            heartbeat = task;
            if (terminal) task.cancel(false);
        }
        synchronized void checkOpen() {
            if (terminal) throw new BusinessException("ASSISTANT_CANCELLED", "客户端已断开，本次运行已取消");
        }
        synchronized void finish(String event, Map<String, ?> payload) {
            if (terminal) return;
            terminal = true;
            if (heartbeat != null) heartbeat.cancel(false);
            try { write(event, payload); }
            catch (IOException | IllegalStateException disconnected) { /* 正式终态通过GET恢复。 */ }
            emitter.complete();
        }
        private void write(String event, Map<String, ?> payload) throws IOException {
            long seq = ++sequence;
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("eventId", runId + ":" + seq); envelope.put("runId", runId); envelope.put("seq", seq);
            envelope.putAll(payload);
            emitter.send(SseEmitter.event().id(runId + ":" + seq).name(event).data(envelope));
        }
        void disconnected() {
            synchronized (this) {
                if (terminal) return;
                terminal = true;
                if (heartbeat != null) heartbeat.cancel(false);
            }
            try { executions.cancel(owner, runId); }
            finally { persistence.cancel(owner, runId); }
        }
    }
}
