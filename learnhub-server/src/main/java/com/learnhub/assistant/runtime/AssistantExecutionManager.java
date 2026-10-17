package com.learnhub.assistant.runtime;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.search.SearchUnavailableException;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/** 无排队的有限线程池；不让等待中的请求无限积累。 */
@Component
public class AssistantExecutionManager {
    private final AssistantRunGuard guard;
    private final AssistantLimits limits;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService deadlines;
    private final ConcurrentMap<String, Entry> active = new ConcurrentHashMap<>();

    public AssistantExecutionManager(AssistantRunGuard guard, AssistantLimits limits) {
        this.guard = guard; this.limits = limits;
        workers = new ThreadPoolExecutor(limits.getGlobalConcurrency(), limits.getGlobalConcurrency(),
                0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> daemon(runnable, "assistant-run"));
        deadlines = Executors.newScheduledThreadPool(limits.getGlobalConcurrency() + 1,
                runnable -> daemon(runnable, "assistant-deadline"));
    }

    public DeferredResult<ResponseEntity<?>> submit(Long userId, String requestedId, Runnable identity,
            Function<AssistantRunGuard.Run, ?> operation) {
        String requestId = requestedId == null ? UUID.randomUUID().toString() : requestedId;
        try { if (!UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw new BusinessException("ASSISTANT_INPUT_INVALID", "请求编号须为小写 UUID"); }
        var result = new DeferredResult<ResponseEntity<?>>(limits.getTimeout().toMillis() + 1000);
        String key = userId + ":" + requestId;
        var entry = new Entry(new AssistantIoScope(limits.getTimeout()), result);
        entry.task = new FutureTask<>(() -> {
            try (var io = entry.io) {
                io.attach();
                try (var run = guard.open(userId, requestId, identity)) {
                    try {
                    Object answer = operation.apply(run);
                    run.markSucceeded();
                    result.setResult(ResponseEntity.ok().header("X-Assistant-Request-Id", requestId)
                            .body(ApiResponse.success(answer)));
                    } catch (RuntimeException failure) {
                        run.markFailed(failure instanceof BusinessException business ? business.getCode() : "ASSISTANT_UNAVAILABLE");
                        throw failure;
                    }
                }
            } catch (RuntimeException error) { result.setResult(failure(error)); }
            finally {
                active.remove(key, entry);
                if (entry.timer != null) entry.timer.cancel(false);
                if (entry.poll != null) entry.poll.cancel(false);
            }
            return null;
        }) {
            @Override protected void done() {
                if (isCancelled()) active.remove(key, entry);
            }
        };
        // 先完整构造可取消的 task，再发布给 DELETE 路径。
        if (active.putIfAbsent(key, entry) != null)
            throw new BusinessException("ASSISTANT_BUSY", "该请求正在处理中");
        result.onTimeout(() -> entry.cancel("ASSISTANT_DEADLINE_EXCEEDED", "助理请求已超时"));
        result.onError(error -> entry.cancel("ASSISTANT_CANCELLED", "本次助理请求已取消"));
        result.onCompletion(() -> {
            if (entry.timer != null) entry.timer.cancel(false);
            if (entry.poll != null) entry.poll.cancel(false);
        });
        try {
            workers.execute(entry.task);
            entry.timer = deadlines.schedule(() -> entry.cancel("ASSISTANT_DEADLINE_EXCEEDED", "助理请求已超时"),
                    limits.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            entry.poll = deadlines.scheduleWithFixedDelay(() -> {
                try {
                    if (guard.cancellationRequested(userId, requestId)) entry.cancel("ASSISTANT_CANCELLED", "本次助理请求已取消");
                } catch (RuntimeException error) {
                    entry.cancel("ASSISTANT_RUNTIME_UNAVAILABLE", "学习助理运行保护暂时不可用");
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
            if (entry.task.isDone()) { entry.timer.cancel(false); entry.poll.cancel(false); }
        } catch (RejectedExecutionException error) {
            active.remove(key, entry);
            entry.cancel("ASSISTANT_BUSY", "学习助理当前请求较多，请稍后重试");
        }
        return result;
    }

    public boolean cancel(Long userId, String requestId) {
        // 本实例立即中断；其他实例以同一 user+run 的 Redis 标记中断在途 HTTP/查询。
        var entry = active.get(userId + ":" + requestId);
        boolean registered;
        try { registered = guard.cancel(userId, requestId); }
        finally { if (entry != null) entry.cancel("ASSISTANT_CANCELLED", "本次助理请求已取消"); }
        return entry != null || registered;
    }

    public static ResponseEntity<?> failure(RuntimeException error) {
        String code = error instanceof BusinessException business ? business.getCode()
                : error instanceof SearchUnavailableException ? "SEARCH_UNAVAILABLE" : "ASSISTANT_UNAVAILABLE";
        String message = error instanceof BusinessException business ? business.getMessage() : "学习助理暂时不可用";
        int status = switch (code) {
            case "ASSISTANT_SESSION_EXPIRED" -> 401;
            case "ASSISTANT_OUTPUT_INVALID", "AI_MODEL_PROVIDER_ERROR", "AI_MODEL_MALFORMED_RESPONSE",
                    "AI_MODEL_EMPTY_RESPONSE", "AI_MODEL_MISSING_USAGE", "AI_MODEL_UNKNOWN_FINISH_REASON",
                    "AI_MODEL_TRUNCATED", "AI_MODEL_RESPONSE_TOO_LARGE", "AI_MODEL_CONTENT_FILTERED", "AI_MODEL_ABORTED" -> 502;
            case "ASSISTANT_BUSY", "ASSISTANT_RATE_LIMITED", "ASSISTANT_DAILY_LIMIT", "AI_MODEL_RATE_LIMITED", "AI_MODEL_BUSY" -> 429;
            case "ASSISTANT_DEADLINE_EXCEEDED", "AI_MODEL_TIMEOUT" -> 504;
            case "ASSISTANT_INPUT_INVALID", "ASSISTANT_BUDGET_EXCEEDED", "ASSISTANT_TOOL_LIMIT", "AI_MODEL_INPUT_TOO_LARGE" -> 400;
            case "ASSISTANT_UNAVAILABLE", "ASSISTANT_RUNTIME_UNAVAILABLE", "AI_MODEL_UNAVAILABLE",
                    "AI_MODEL_AUTHENTICATION_FAILED", "AI_MODEL_REQUEST_INVALID", "AI_DISABLED", "SEARCH_UNAVAILABLE" -> 503;
            default -> 409;
        };
        return ResponseEntity.status(status).body(ApiResponse.failure(code, message));
    }

    @PreDestroy public void close() {
        active.values().forEach(entry -> entry.cancel("ASSISTANT_CANCELLED", "服务正在关闭"));
        workers.shutdownNow(); deadlines.shutdownNow(); active.clear();
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }

    private static final class Entry {
        final AssistantIoScope io;
        final DeferredResult<ResponseEntity<?>> result;
        volatile FutureTask<Void> task;
        volatile ScheduledFuture<?> timer;
        volatile ScheduledFuture<?> poll;
        Entry(AssistantIoScope io, DeferredResult<ResponseEntity<?>> result) { this.io = io; this.result = result; }
        void cancel(String code, String message) {
            result.setResult(failure(new BusinessException(code, message)));
            if (poll != null) poll.cancel(false);
            if (task != null) task.cancel(true);
            io.cancel();
        }
    }
}
