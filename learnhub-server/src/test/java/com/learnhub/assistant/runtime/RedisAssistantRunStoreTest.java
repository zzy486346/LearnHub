package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisAssistantRunStoreTest {
    @Test
    @SuppressWarnings("unchecked")
    void mapsAtomicScriptResultsAndChecksExactCancelOwner() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-2L, -1L, 1L, 1L);
        when(values.get(any())).thenReturn("owner");
        RedisAssistantRunStore store = new RedisAssistantRunStore(redis);

        assertThat(store.acquire("user", "owner", 75_000, 4, 6))
                .isEqualTo(AssistantRunStore.AcquireResult.GLOBAL_BUSY);
        assertThat(store.reserveDaily("user", "2026-10-16", 20, 120_000, 800, 86_400))
                .isEqualTo(AssistantRunStore.ReserveResult.DAILY_CALL_LIMIT);
        assertThat(store.cancel("user", "owner", 75_000)).isTrue();
        assertThat(store.isCancelled("user", "owner")).isTrue();
        store.release("user", "owner");

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                "learnhub:assistant:{runtime}:active:user",
                "learnhub:assistant:{runtime}:global-leases",
                "learnhub:assistant:{runtime}:cancel:user:owner")), eq("owner"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullScriptResultFailsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        RedisAssistantRunStore store = new RedisAssistantRunStore(redis);

        assertThatThrownBy(() -> store.acquire("user", "owner", 75_000, 4, 6))
                .isInstanceOf(IllegalStateException.class);
    }
}
