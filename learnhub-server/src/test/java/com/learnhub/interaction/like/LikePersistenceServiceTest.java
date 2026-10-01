package com.learnhub.interaction.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LikePersistenceServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void successfulBatchReentryDoesNotAccumulateCountTwice() {
        LikeRecordMapper recordMapper = mock(LikeRecordMapper.class);
        LikeSyncBatchMapper batchMapper = mock(LikeSyncBatchMapper.class);
        LikeCountMapper countMapper = mock(LikeCountMapper.class);
        LikeEventInboxService inbox = mock(LikeEventInboxService.class);
        LikePersistenceService service = new LikePersistenceService(recordMapper, batchMapper, countMapper, inbox);
        LikeSyncBatchEntity completed = new LikeSyncBatchEntity();
        completed.setBatchId("batch-1");
        completed.setStatus("SUCCESS");
        when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(null, completed);
        List<LikeRelationState> relations = List.of(
                new LikeRelationState(7L, LikeTargetType.COURSE, 1002L, true, 1L));
        List<LikeCountDelta> deltas = List.of(
                new LikeCountDelta(LikeTargetType.COURSE, 1002L, 1L));
        LikeEventInboxEntity pending = new LikeEventInboxEntity();
        pending.setEventId("event-1");
        pending.setUserId(7L);
        pending.setTargetType("COURSE");
        pending.setTargetId(1002L);
        pending.setLiked(true);
        pending.setEventSequence(1L);
        when(inbox.lockPending(List.of("event-1"))).thenReturn(List.of(pending));

        boolean first = service.applyBatch("batch-1", relations, deltas, List.of("event-1"));
        boolean repeated = service.applyBatch("batch-1", relations, deltas, List.of("event-1"));

        assertThat(first).isTrue();
        assertThat(repeated).isTrue();
        verify(recordMapper, times(1)).upsertBatch(anyList());
        verify(countMapper, times(1)).updateCourseCounts(deltas);
        verify(batchMapper, times(1)).insert(any(LikeSyncBatchEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void processedEventInAnotherBatchDoesNotAccumulateAgain() {
        LikeRecordMapper recordMapper = mock(LikeRecordMapper.class);
        LikeSyncBatchMapper batchMapper = mock(LikeSyncBatchMapper.class);
        LikeCountMapper countMapper = mock(LikeCountMapper.class);
        LikeEventInboxService inbox = mock(LikeEventInboxService.class);
        LikePersistenceService service = new LikePersistenceService(recordMapper, batchMapper, countMapper, inbox);
        LikeEventInboxEntity pending = new LikeEventInboxEntity();
        pending.setEventId("event-1");
        pending.setUserId(7L);
        pending.setTargetType("COURSE");
        pending.setTargetId(1002L);
        pending.setLiked(true);
        pending.setEventSequence(1L);
        when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(inbox.lockPending(List.of("event-1"))).thenReturn(List.of(pending), List.of());

        service.applyBatch("batch-a", List.of(), List.of(), List.of("event-1"));
        service.applyBatch("batch-b", List.of(), List.of(), List.of("event-1"));

        verify(countMapper, times(1)).updateCourseCounts(anyList());
        verify(recordMapper, times(1)).upsertBatch(anyList());
    }
}
