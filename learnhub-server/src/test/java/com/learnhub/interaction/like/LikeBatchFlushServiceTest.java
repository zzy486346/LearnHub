package com.learnhub.interaction.like;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LikeBatchFlushServiceTest {
    private StringRedisTemplate redis;
    private LikePersistenceService persistence;
    private HashOperations<String, Object, Object> hashes;
    private SetOperations<String, String> sets;
    private LikeBatchFlushService service;
    private LikeEventInboxService inbox;
    private LikeEventAggregationService aggregation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        persistence = mock(LikePersistenceService.class);
        hashes = mock(HashOperations.class);
        sets = mock(SetOperations.class);
        inbox = mock(LikeEventInboxService.class);
        aggregation = mock(LikeEventAggregationService.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForSet()).thenReturn(sets);
        service = new LikeBatchFlushService(redis, persistence, inbox, aggregation);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulClaimIsDeletedAfterDatabaseFlush() {
        when(hashes.entries(LikeRedisKeys.batchRelations("batch-1")))
                .thenReturn(Map.of("COURSE:1002:7", "1|1|event-1"));
        when(hashes.entries(LikeRedisKeys.batchDelta("batch-1")))
                .thenReturn(Map.of("COURSE:1002", "1"));
        when(hashes.keys(LikeRedisKeys.batchEvents("batch-1"))).thenReturn(Set.of("event-1"));
        when(persistence.applyBatch(eq("batch-1"), anyList(), anyList(), anyList())).thenReturn(true);

        service.process("batch-1");

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                LikeRedisKeys.batchDelta("batch-1"),
                LikeRedisKeys.batchRelations("batch-1"),
                LikeRedisKeys.batchEvents("batch-1"),
                LikeRedisKeys.PROCESSING_BATCHES)), eq("batch-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDatabaseFlushKeepsClaimedBatchForRetry() {
        when(hashes.entries(LikeRedisKeys.batchRelations("batch-1"))).thenReturn(Map.of());
        when(hashes.entries(LikeRedisKeys.batchDelta("batch-1")))
                .thenReturn(Map.of("COURSE:1002", "1"));
        when(hashes.keys(LikeRedisKeys.batchEvents("batch-1"))).thenReturn(Set.of("event-1"));
        when(persistence.applyBatch(eq("batch-1"), anyList(), anyList(), anyList())).thenReturn(false);

        service.process("batch-1");

        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void retainedBatchIsRetriedOnNextFlush() {
        when(sets.members(LikeRedisKeys.PROCESSING_BATCHES)).thenReturn(Set.of("batch-1"));
        when(hashes.entries(LikeRedisKeys.batchRelations("batch-1"))).thenReturn(Map.of());
        when(hashes.entries(LikeRedisKeys.batchDelta("batch-1"))).thenReturn(Map.of());
        when(hashes.keys(LikeRedisKeys.batchEvents("batch-1"))).thenReturn(Set.of());
        when(persistence.applyBatch("batch-1", List.of(), List.of(), List.of())).thenReturn(false);

        service.retryProcessingBatches();

        verify(persistence, times(1)).applyBatch("batch-1", List.of(), List.of(), List.of());
    }
}
