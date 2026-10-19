package com.learnhub.assistant.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/** 模型烟测使用真实RunGuard，只有Redis存储换成独立的持久化批次预留回调。 */
public final class AssistantGuardTestSupport {
    private AssistantGuardTestSupport() { }

    public static AssistantRunGuard guard(AssistantLimits limits, IntPredicate reserve) {
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        return new AssistantRunGuard(new AssistantRunStore() {
            @Override public AcquireResult acquire(String user, String owner, long lease, int global, int rpm) {
                return AcquireResult.ACQUIRED;
            }
            @Override public ReserveResult reserveDaily(String user, String day, int calls, int tokens, int amount, long ttl) {
                return reserve.test(amount) ? ReserveResult.RESERVED : ReserveResult.DAILY_TOKEN_LIMIT;
            }
            @Override public boolean isCancelled(String user, String owner) { return cancelled.contains(owner); }
            @Override public boolean cancel(String user, String owner, long ttl) { cancelled.add(owner); return true; }
            @Override public void release(String user, String owner) { }
        }, limits);
    }
}
