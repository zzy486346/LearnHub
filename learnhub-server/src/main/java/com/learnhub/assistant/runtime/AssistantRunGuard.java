package com.learnhub.assistant.runtime;

import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class AssistantRunGuard {
    private static final Logger log = LoggerFactory.getLogger(AssistantRunGuard.class);
    private static final int MAX_REQUEST_ID_LENGTH = 100;
    private static final int MAX_TOOL_SIGNATURE_LENGTH = 4_096;

    private final AssistantRunStore store;
    private final AssistantLimits limits;
    private final Clock clock;

    @Autowired
    public AssistantRunGuard(AssistantRunStore store, AssistantLimits limits) {
        this(store, limits, Clock.systemUTC());
    }

    AssistantRunGuard(AssistantRunStore store, AssistantLimits limits, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    public Run open(Long userId, String requestId, Runnable identityCheck) {
        if (userId == null || userId <= 0 || requestId == null || requestId.isBlank()
                || requestId.length() > MAX_REQUEST_ID_LENGTH || identityCheck == null) {
            throw new IllegalArgumentException("Invalid assistant run identity");
        }
        identityCheck.run();
        String userHash = hash("user:" + userId, 24);
        String owner = owner(userHash, requestId);
        long leaseMillis = safeMillis(limits.getTimeout().plus(limits.getLeaseGrace()));
        AssistantRunStore.AcquireResult result;
        try {
            result = store.acquire(userHash, owner, leaseMillis,
                    limits.getGlobalConcurrency(), limits.getRequestsPerMinute());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (result == AssistantRunStore.AcquireResult.USER_BUSY
                || result == AssistantRunStore.AcquireResult.GLOBAL_BUSY) {
            throw new BusinessException("ASSISTANT_BUSY", "学习助理当前请求较多，请稍后重试");
        }
        if (result == AssistantRunStore.AcquireResult.RATE_LIMITED) {
            throw new BusinessException("ASSISTANT_RATE_LIMITED", "学习助理请求过于频繁，请稍后重试");
        }
        return new Run(requestId, userHash, owner, identityCheck, clock.instant());
    }

    public boolean cancel(Long userId, String requestId) {
        if (userId == null || userId <= 0 || requestId == null || requestId.isBlank()
                || requestId.length() > MAX_REQUEST_ID_LENGTH) return false;
        try {
            String userHash = hash("user:" + userId, 24);
            return store.cancel(userHash, owner(userHash, requestId),
                    safeMillis(limits.getTimeout().plus(limits.getLeaseGrace())));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    public boolean cancellationRequested(Long userId, String requestId) {
        try {
            String userHash = hash("user:" + userId, 24);
            return store.isCancelled(userHash, owner(userHash, requestId));
        } catch (RuntimeException exception) { throw unavailable(exception); }
    }

    public final class Run implements AutoCloseable {
        private final String requestId;
        private final String userHash;
        private final String owner;
        private final Runnable identityCheck;
        private final Instant startedAt;
        private final Instant deadline;
        private final Set<String> toolSignatures = new HashSet<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private int modelCalls;
        private int toolDecisionRounds;
        private int toolCalls;
        private int usageRecords;
        private long reservedTokens;
        private long actualPromptTokens;
        private long actualCompletionTokens;
        private long actualTotalTokens;
        private String status = "RUNNING";

        private Run(String requestId, String userHash, String owner, Runnable identityCheck, Instant startedAt) {
            this.requestId = requestId;
            this.userHash = userHash;
            this.owner = owner;
            this.identityCheck = identityCheck;
            this.startedAt = startedAt;
            this.deadline = startedAt.plus(limits.getTimeout());
        }

        public void check() {
            requireOpen();
            if (Thread.currentThread().isInterrupted()) {
                status = "CANCELLED";
                throw new BusinessException("ASSISTANT_CANCELLED", "学习助理请求已取消");
            }
            if (!clock.instant().isBefore(deadline)) {
                status = "DEADLINE_EXCEEDED";
                throw new BusinessException("ASSISTANT_DEADLINE_EXCEEDED", "学习助理处理超时");
            }
            try {
                if (store.isCancelled(userHash, owner)) {
                    status = "CANCELLED";
                    throw new BusinessException("ASSISTANT_CANCELLED", "学习助理请求已取消");
                }
            } catch (BusinessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                status = "RUNTIME_UNAVAILABLE";
                throw unavailable(exception);
            }
            try {
                identityCheck.run();
            } catch (RuntimeException exception) {
                status = "IDENTITY_REJECTED";
                throw exception;
            }
        }

        public Duration remaining() {
            Duration remaining = Duration.between(clock.instant(), deadline);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        public synchronized void reserveModel(int estimatedInputTokens, int requestedOutputTokens) {
            check();
            if (estimatedInputTokens < 0 || requestedOutputTokens < 1
                    || requestedOutputTokens > limits.getMaxOutputTokens()) {
                status = "BUDGET_REJECTED";
                throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "模型输入或输出预算无效");
            }
            long reservation = (long) estimatedInputTokens + requestedOutputTokens;
            if (modelCalls >= limits.getMaxModelCalls()
                    || reservation > limits.getMaxRunTokens() - reservedTokens) {
                status = "BUDGET_REJECTED";
                throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "本次学习助理运行已达到模型调用预算");
            }
            Instant now = clock.instant();
            String utcDay = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
            long ttlSeconds = Math.max(1, Duration.between(now,
                    LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(2)
                            .atStartOfDay(ZoneOffset.UTC).toInstant()).getSeconds());
            AssistantRunStore.ReserveResult result;
            try {
                result = store.reserveDaily(userHash, utcDay, limits.getDailyModelCalls(),
                        limits.getDailyReservedTokens(), Math.toIntExact(reservation), ttlSeconds);
            } catch (RuntimeException exception) {
                status = "RUNTIME_UNAVAILABLE";
                throw unavailable(exception);
            }
            if (result != AssistantRunStore.ReserveResult.RESERVED) {
                status = "DAILY_LIMIT_REJECTED";
                throw new BusinessException("ASSISTANT_DAILY_LIMIT", "今日学习助理模型额度已用完");
            }
            modelCalls++;
            reservedTokens += reservation;
        }

        public synchronized void recordUsage(long promptTokens, long completionTokens, long totalTokens) {
            requireOpen();
            if (usageRecords >= modelCalls || promptTokens < 0 || completionTokens < 0
                    || totalTokens < promptTokens + completionTokens) {
                throw new IllegalArgumentException("Invalid model usage");
            }
            actualPromptTokens = Math.addExact(actualPromptTokens, promptTokens);
            actualCompletionTokens = Math.addExact(actualCompletionTokens, completionTokens);
            actualTotalTokens = Math.addExact(actualTotalTokens, totalTokens);
            usageRecords++;
            if (actualTotalTokens > limits.getMaxRunTokens()) {
                status = "BUDGET_REJECTED";
                throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "模型报告的实际用量已超过本次运行上限");
            }
        }

        public synchronized void toolDecisionRound() {
            check();
            if (toolDecisionRounds >= limits.getMaxToolDecisionRounds()) {
                status = "TOOL_LIMIT_REJECTED";
                throw new BusinessException("ASSISTANT_TOOL_LIMIT", "学习助理已达到工具决策轮次上限");
            }
            toolDecisionRounds++;
        }

        public synchronized void toolCall(String signature) {
            check();
            if (signature == null || signature.isBlank() || signature.length() > MAX_TOOL_SIGNATURE_LENGTH) {
                throw new IllegalArgumentException("Invalid tool signature");
            }
            if (toolCalls >= limits.getMaxToolCalls()) {
                status = "TOOL_LIMIT_REJECTED";
                throw new BusinessException("ASSISTANT_TOOL_LIMIT", "学习助理已达到工具调用次数上限");
            }
            String signatureHash = hash("tool:" + signature.trim(), 32);
            if (!toolSignatures.add(signatureHash)) {
                status = "TOOL_LOOP_REJECTED";
                throw new BusinessException("ASSISTANT_TOOL_LOOP", "学习助理停止了重复的工具调用");
            }
            toolCalls++;
        }

        public String requestId() { return requestId; }

        public synchronized AssistantRunSummary summary() {
            return new AssistantRunSummary(requestId, modelCalls, toolDecisionRounds, toolCalls,
                    reservedTokens, actualPromptTokens, actualCompletionTokens, actualTotalTokens,
                    usageRecords == modelCalls, status, elapsedMillis());
        }

        public synchronized void markSucceeded() {
            check();
            status = "SUCCEEDED";
        }

        /** 仅供执行管理器在最终checkpoint和持久化提交成功后使用，提交后不再做可失败的远程复核。 */
        public synchronized void markCommittedSucceeded() {
            requireOpen();
            status = "SUCCEEDED";
        }

        public synchronized void markFailed(String code) {
            if (!closed.get()) status = code != null && code.matches("[A-Z_]{1,64}") ? code : "FAILED";
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (this) {
                if (Objects.equals(status, "RUNNING")) status = "INCOMPLETE";
            }
            try {
                store.release(userHash, owner);
            } catch (RuntimeException exception) {
                log.warn("Assistant run lease release failed requestId={}", requestId);
            }
            AssistantRunSummary summary = summary();
            log.info("Assistant run closed requestId={} user={} status={} modelCalls={} toolRounds={} "
                            + "toolCalls={} reservedTokens={} actualTokens={} usageComplete={} elapsedMs={}",
                    requestId, userHash, summary.status(), summary.modelCalls(), summary.toolDecisionRounds(),
                    summary.toolCalls(), summary.reservedTokens(), summary.actualTotalTokens(),
                    summary.usageComplete(), summary.elapsedMillis());
        }

        private void requireOpen() {
            if (closed.get()) throw new IllegalStateException("Assistant run is closed");
        }

        private long elapsedMillis() {
            return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
        }
    }

    private static BusinessException unavailable(RuntimeException cause) {
        return new BusinessException("ASSISTANT_RUNTIME_UNAVAILABLE", "学习助理运行保护暂时不可用");
    }

    private static long safeMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Assistant duration is too large", exception);
        }
    }

    private static String owner(String userHash, String requestId) {
        return hash("request:" + userHash + ":" + requestId, 32);
    }

    private static String hash(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
