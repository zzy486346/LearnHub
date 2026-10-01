package com.learnhub.interaction.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LikeEventAggregationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void batchAggregationUsesEventDedupKeysForIdempotency() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LikeEventInboxService inbox = mock(LikeEventInboxService.class);
        LikeEventAggregationService service = new LikeEventAggregationService(redis, inbox);
        LikeEvent first = event("event-1", 7L, true);
        LikeEvent duplicate = event("event-1", 7L, true);
        when(inbox.persistAndGetPending(anyList())).thenReturn(List.of(first, duplicate));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);

        long processed = service.aggregate(List.of(first, duplicate));

        assertThat(processed).isEqualTo(1L);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                LikeRedisKeys.LIVE_DELTA,
                LikeRedisKeys.LIVE_RELATIONS,
                LikeRedisKeys.PENDING_PUBLISH,
                LikeRedisKeys.LIVE_EVENTS,
                LikeRedisKeys.RECONCILE_LOCK,
                LikeRedisKeys.eventDedup("event-1"),
                LikeRedisKeys.eventDedup("event-1"));
    }

    @Test
    void processedInboxEventIsNotAggregatedAgain() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LikeEventInboxService inbox = mock(LikeEventInboxService.class);
        LikeEventAggregationService service = new LikeEventAggregationService(redis, inbox);
        LikeEvent event = event("processed", 7L, true);
        when(inbox.persistAndGetPending(List.of(event))).thenReturn(List.of());

        assertThat(service.aggregate(List.of(event))).isZero();

        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void unsupportedVersionIsRejectedBeforeInboxPersistence() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LikeEventInboxService inbox = mock(LikeEventInboxService.class);
        LikeEventAggregationService service = new LikeEventAggregationService(redis, inbox);
        LikeEvent event = new LikeEvent("future", 7L, LikeTargetType.COURSE, 1002L,
                true, 2L, Instant.now(), 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.aggregate(List.of(event)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(inbox, never()).persistAndGetPending(anyList());
    }

    private LikeEvent event(String eventId, Long userId, boolean liked) {
        return new LikeEvent(eventId, userId, LikeTargetType.COURSE, 1002L,
                liked, 1L, Instant.parse("2026-09-30T00:00:00Z"), 1);
    }
}
