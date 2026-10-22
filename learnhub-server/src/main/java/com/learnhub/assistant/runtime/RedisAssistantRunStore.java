package com.learnhub.assistant.runtime;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
final class RedisAssistantRunStore implements AssistantRunStore {
    private static final String DEFAULT_PREFIX = "learnhub:assistant:{runtime}:";
    private static final DefaultRedisScript<Long> ACQUIRE = script("lua/assistant_run_acquire.lua");
    private static final DefaultRedisScript<Long> RESERVE = script("lua/assistant_daily_reserve.lua");
    private static final DefaultRedisScript<Long> CANCEL = script("lua/assistant_run_cancel.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("lua/assistant_run_release.lua");

    private final StringRedisTemplate redis;
    private final String prefix;
    private final String globalLeases;

    @Autowired
    RedisAssistantRunStore(StringRedisTemplate redis) {
        this(redis, DEFAULT_PREFIX);
    }

    RedisAssistantRunStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        if (prefix == null || prefix.isBlank() || !prefix.endsWith(":")) {
            throw new IllegalArgumentException("Assistant Redis prefix must end with ':'");
        }
        this.prefix = prefix;
        this.globalLeases = prefix + "global-leases";
    }

    @Override
    public AcquireResult acquire(String userHash, String owner, long leaseMillis,
                                 int globalLimit, int requestsPerMinute) {
        Long result = redis.execute(ACQUIRE,
                List.of(activeKey(userHash), globalLeases, rateKey(userHash), rateSequenceKey(userHash)),
                owner, Long.toString(leaseMillis), Integer.toString(globalLimit),
                Integer.toString(requestsPerMinute));
        if (result == null) throw new IllegalStateException("Assistant run acquire returned no result");
        return switch (result.intValue()) {
            case 1 -> AcquireResult.ACQUIRED;
            case -1 -> AcquireResult.USER_BUSY;
            case -2 -> AcquireResult.GLOBAL_BUSY;
            case -3 -> AcquireResult.RATE_LIMITED;
            default -> throw new IllegalStateException("Unexpected assistant acquire result: " + result);
        };
    }

    @Override
    public ReserveResult reserveDaily(String userHash, String utcDay, int dailyModelCalls,
                                      int dailyReservedTokens, int tokens, long ttlSeconds) {
        return reserveDailyBatch(userHash, utcDay, dailyModelCalls, dailyReservedTokens, tokens, ttlSeconds, 1);
    }

    @Override
    public ReserveResult reserveDailyBatch(String userHash, String utcDay, int dailyModelCalls,
            int dailyReservedTokens, int tokens, long ttlSeconds, int callIncrement) {
        if (callIncrement != 1 && callIncrement != 2) {
            throw new IllegalArgumentException("Assistant daily call increment must be 1 or 2");
        }
        Long result = redis.execute(RESERVE,
                List.of(prefix + "daily:" + utcDay + ":" + userHash + ":calls",
                        prefix + "daily:" + utcDay + ":" + userHash + ":tokens",
                        prefix + "daily:" + utcDay + ":global:calls",
                        prefix + "daily:" + utcDay + ":global:tokens"),
                Integer.toString(dailyModelCalls), Integer.toString(dailyReservedTokens),
                Integer.toString(tokens), Long.toString(ttlSeconds), Integer.toString(callIncrement));
        if (result == null) throw new IllegalStateException("Assistant daily reservation returned no result");
        return switch (result.intValue()) {
            case 1 -> ReserveResult.RESERVED;
            case -1 -> ReserveResult.DAILY_CALL_LIMIT;
            case -2 -> ReserveResult.DAILY_TOKEN_LIMIT;
            case -3 -> throw new IllegalStateException("Assistant daily reservation rejected call increment");
            default -> throw new IllegalStateException("Unexpected assistant reserve result: " + result);
        };
    }

    @Override
    public boolean isCancelled(String userHash, String owner) {
        return owner.equals(redis.opsForValue().get(cancelKey(userHash, owner)));
    }

    @Override
    public boolean cancel(String userHash, String owner, long ttlMillis) {
        Long result = redis.execute(CANCEL, List.of(activeKey(userHash), cancelKey(userHash, owner)),
                owner, Long.toString(ttlMillis));
        if (result == null) throw new IllegalStateException("Assistant cancellation returned no result");
        return result == 1L;
    }

    @Override
    public void release(String userHash, String owner) {
        Long result = redis.execute(RELEASE,
                List.of(activeKey(userHash), globalLeases, cancelKey(userHash, owner)), owner);
        if (result == null) throw new IllegalStateException("Assistant run release returned no result");
    }

    private String activeKey(String userHash) { return prefix + "active:" + userHash; }
    private String rateKey(String userHash) { return prefix + "rate:" + userHash; }
    private String rateSequenceKey(String userHash) { return prefix + "rate-sequence:" + userHash; }
    private String cancelKey(String userHash, String owner) { return prefix + "cancel:" + userHash + ":" + owner; }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
