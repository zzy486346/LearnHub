package com.learnhub.assistant.runtime;

import com.learnhub.common.exception.BusinessException;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只在助理工作线程生效；取消时同时通知当前 SQL/ES 操作。 */
public final class AssistantIoScope implements AutoCloseable {
    private static final ThreadLocal<AssistantIoScope> CURRENT = new ThreadLocal<>();
    private final long deadline;
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public AssistantIoScope(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("助理 IO 超时范围无效");
        deadline = System.nanoTime() + timeout.toNanos();
    }

    public void attach() {
        if (CURRENT.get() != null) throw new IllegalStateException("助理 IO scope 不允许嵌套");
        CURRENT.set(this);
    }

    public static AssistantIoScope current() { return CURRENT.get(); }

    public Duration remaining() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new BusinessException("ASSISTANT_CANCELLED", "本次助理请求已取消");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new BusinessException("ASSISTANT_DEADLINE_EXCEEDED", "助理请求已超时");
        return Duration.ofNanos(nanos);
    }

    public AutoCloseable onCancel(Runnable action) {
        cancellations.add(action);
        if (cancelled.get()) action.run();
        return () -> cancellations.remove(action);
    }

    public void track(Statement statement) throws SQLException {
        statement.setQueryTimeout(Math.max(1, (int) Math.ceil(remaining().toMillis() / 1000.0)));
        onCancel(() -> {
            // Connector/J 的 isClosed() 会等待执行连接锁，先调用它会让取消退化为等待查询超时。
            try { statement.cancel(); }
            catch (SQLException ignored) { /* 查询本身仍受驱动超时约束。 */ }
        });
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable action : cancellations) {
                try { action.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    @Override public void close() {
        cancellations.clear();
        if (CURRENT.get() == this) CURRENT.remove();
    }
}
