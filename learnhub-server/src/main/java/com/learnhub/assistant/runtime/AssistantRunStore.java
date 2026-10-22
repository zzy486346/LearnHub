package com.learnhub.assistant.runtime;

interface AssistantRunStore {
    enum AcquireResult { ACQUIRED, USER_BUSY, GLOBAL_BUSY, RATE_LIMITED }
    enum ReserveResult { RESERVED, DAILY_CALL_LIMIT, DAILY_TOKEN_LIMIT }

    AcquireResult acquire(String userHash, String owner, long leaseMillis, int globalLimit, int requestsPerMinute);

    ReserveResult reserveDaily(String userHash, String utcDay, int dailyModelCalls,
                               int dailyReservedTokens, int tokens, long ttlSeconds);

    /** 旧存储实现必须失败关闭，不能用两次单笔预留伪造批次原子性。 */
    default ReserveResult reserveDailyBatch(String userHash, String utcDay, int dailyModelCalls,
            int dailyReservedTokens, int tokens, long ttlSeconds, int callIncrement) {
        return ReserveResult.DAILY_CALL_LIMIT;
    }

    boolean isCancelled(String userHash, String owner);

    boolean cancel(String userHash, String owner, long ttlMillis);

    void release(String userHash, String owner);
}
