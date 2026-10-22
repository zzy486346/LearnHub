package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class AssistantRunRedisIntegrationTest {
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisAssistantRunStore store;
    private String prefix;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("LEARNHUB_ASSISTANT_REDIS_IT")),
                "set LEARNHUB_ASSISTANT_REDIS_IT=true to run real Redis assistant guard tests");
        String host = environment("LEARNHUB_ASSISTANT_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(environment("LEARNHUB_ASSISTANT_REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        String namespace = "runtime-it-" + UUID.randomUUID().toString().replace("-", "");
        prefix = "learnhub:assistant:{" + namespace + "}:";
        store = new RedisAssistantRunStore(redis, prefix);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && prefix != null && prefix.startsWith("learnhub:assistant:{runtime-it-")) {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void globalConcurrencyIsAtomicAcrossSimultaneousUsers() throws Exception {
        int attempts = 8;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<AssistantRunStore.AcquireResult>> futures = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                int id = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.acquire("user-" + id, "owner-" + id, 75_000, 2, 6);
                }));
            }
            ready.await();
            start.countDown();
            List<AssistantRunStore.AcquireResult> results = new ArrayList<>();
            for (Future<AssistantRunStore.AcquireResult> future : futures) results.add(future.get());

            assertThat(results).filteredOn(result -> result == AssistantRunStore.AcquireResult.ACQUIRED).hasSize(2);
            assertThat(results).filteredOn(result -> result == AssistantRunStore.AcquireResult.GLOBAL_BUSY).hasSize(6);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelRequiresExactOwnerAndReleaseFreesLeaseWithoutLeakingCancelFlag() {
        assertThat(store.acquire("user-a", "owner-a", 75_000, 4, 2))
                .isEqualTo(AssistantRunStore.AcquireResult.ACQUIRED);
        assertThat(store.acquire("user-a", "owner-b", 75_000, 4, 2))
                .isEqualTo(AssistantRunStore.AcquireResult.USER_BUSY);
        assertThat(store.cancel("user-a", "owner-b", 75_000)).isFalse();
        assertThat(store.cancel("user-a", "owner-a", 75_000)).isTrue();
        assertThat(store.isCancelled("user-a", "owner-a")).isTrue();

        store.release("user-a", "owner-a");

        assertThat(store.isCancelled("user-a", "owner-a")).isFalse();
        assertThat(store.acquire("user-a", "owner-a", 75_000, 4, 2))
                .isEqualTo(AssistantRunStore.AcquireResult.ACQUIRED);
        store.release("user-a", "owner-a");
        assertThat(store.acquire("user-a", "owner-a", 75_000, 4, 2))
                .isEqualTo(AssistantRunStore.AcquireResult.RATE_LIMITED);
    }

    @Test
    void dailyReservationEnforcesUserAndGlobalCallAndTokenCapsAtomically() {
        assertThat(store.reserveDaily("user-a", "calls", 1, 100, 40, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDaily("user-a", "calls", 1, 100, 40, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_CALL_LIMIT);

        assertThat(store.reserveDaily("user-a", "global-calls", 2, 100, 40, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDaily("user-b", "global-calls", 2, 100, 40, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDaily("user-c", "global-calls", 2, 100, 1, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_CALL_LIMIT);

        assertThat(store.reserveDaily("user-a", "global-tokens", 10, 100, 60, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDaily("user-b", "global-tokens", 10, 100, 41, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_TOKEN_LIMIT);
    }

    @Test
    void summaryBatchReservationConsumesTwoGlobalCallsAtomically() {
        assertThat(store.reserveDailyBatch("user-a", "summary-full", 2, 2_000, 850, 300, 2))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDaily("user-b", "summary-full", 2, 2_000, 100, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_CALL_LIMIT);

        assertThat(store.reserveDaily("user-a", "summary-one-left", 2, 2_000, 100, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
        assertThat(store.reserveDailyBatch("user-b", "summary-one-left", 2, 2_000, 850, 300, 2))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_CALL_LIMIT);
        assertThat(store.reserveDaily("user-b", "summary-one-left", 2, 2_000, 100, 300))
                .isEqualTo(AssistantRunStore.ReserveResult.RESERVED);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test void legacyFourArgumentScriptCallerStillReservesOneCall() {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("lua/assistant_daily_reserve.lua"));
        script.setResultType(Long.class);
        List<String> keys = List.of(prefix + "legacy:user:calls", prefix + "legacy:user:tokens",
                prefix + "legacy:global:calls", prefix + "legacy:global:tokens");
        assertThat(redis.execute(script, keys, "5", "1000", "100", "300")).isEqualTo(1L);
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("1");
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("1");
    }
}
