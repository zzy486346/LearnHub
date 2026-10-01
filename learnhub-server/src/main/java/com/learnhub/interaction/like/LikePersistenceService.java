package com.learnhub.interaction.like;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikePersistenceService {
    private final LikeRecordMapper recordMapper;
    private final LikeSyncBatchMapper batchMapper;
    private final LikeCountMapper countMapper;
    private final LikeEventInboxService inbox;

    public LikePersistenceService(LikeRecordMapper recordMapper,
                                  LikeSyncBatchMapper batchMapper,
                                  LikeCountMapper countMapper,
                                  LikeEventInboxService inbox) {
        this.recordMapper = recordMapper;
        this.batchMapper = batchMapper;
        this.countMapper = countMapper;
        this.inbox = inbox;
    }

    @Transactional
    public boolean applyBatch(String batchId,
                              List<LikeRelationState> relations,
                              List<LikeCountDelta> deltas,
                              List<String> eventIds) {
        LikeSyncBatchEntity existing = findBatch(batchId);
        if (existing != null && "SUCCESS".equals(existing.getStatus())) return true;

        List<LikeEventInboxEntity> pendingEvents = inbox.lockPending(eventIds.stream().distinct().toList());
        relations = deriveRelations(pendingEvents);
        deltas = deriveDeltas(pendingEvents);

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            LikeSyncBatchEntity batch = new LikeSyncBatchEntity();
            batch.setBatchId(batchId);
            batch.setStatus("PROCESSING");
            batch.setRelationCount(relations.size());
            batch.setTargetCount(deltas.size());
            batch.setCreatedAt(now);
            batch.setUpdatedAt(now);
            try {
                batchMapper.insert(batch);
            } catch (DuplicateKeyException duplicate) {
                LikeSyncBatchEntity concurrent = findBatch(batchId);
                return concurrent != null && "SUCCESS".equals(concurrent.getStatus());
            }
        }

        if (!relations.isEmpty()) {
            List<LikeRecordEntity> records = toEntities(relations, now);
            for (int from = 0; from < records.size(); from += 500) {
                recordMapper.upsertBatch(records.subList(from, Math.min(from + 500, records.size())));
            }
        }
        updateCounts(deltas);
        inbox.markProcessed(eventIds, now);
        batchMapper.markSuccess(batchId, now);
        return true;
    }

    public List<LikeRecordEntity> listTargetRecords(LikeTargetType targetType, Long targetId) {
        return recordMapper.selectList(new LambdaQueryWrapper<LikeRecordEntity>()
                .eq(LikeRecordEntity::getTargetType, targetType.name())
                .eq(LikeRecordEntity::getTargetId, targetId));
    }

    public List<LikeRecordEntity> listAllRecords() {
        return recordMapper.selectList(null);
    }

    @Transactional
    public void reconcileTarget(LikeTargetType targetType, Long targetId, List<Long> activeUsers, long fence) {
        List<LikeRecordEntity> existing = listTargetRecords(targetType, targetId);
        Map<Long, Boolean> desired = new java.util.HashMap<>();
        existing.forEach(record -> desired.put(record.getUserId(), false));
        activeUsers.forEach(userId -> desired.put(userId, true));
        List<LikeRelationState> states = desired.entrySet().stream()
                .map(entry -> new LikeRelationState(entry.getKey(), targetType, targetId, entry.getValue(), fence))
                .toList();
        if (!states.isEmpty()) recordMapper.upsertBatch(toEntities(states, LocalDateTime.now()));
        setAbsoluteCount(targetType, targetId, activeUsers.size());
    }

    private LikeSyncBatchEntity findBatch(String batchId) {
        return batchMapper.selectOne(new LambdaQueryWrapper<LikeSyncBatchEntity>()
                .eq(LikeSyncBatchEntity::getBatchId, batchId)
                .last("LIMIT 1"));
    }

    private List<LikeRecordEntity> toEntities(List<LikeRelationState> states, LocalDateTime now) {
        List<LikeRecordEntity> records = new ArrayList<>(states.size());
        for (LikeRelationState state : states) {
            LikeRecordEntity record = new LikeRecordEntity();
            record.setId(IdWorker.getId());
            record.setUserId(state.userId());
            record.setTargetType(state.targetType().name());
            record.setTargetId(state.targetId());
            record.setActive(state.active());
            record.setEventSequence(state.sequence());
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            records.add(record);
        }
        return records;
    }

    private List<LikeRelationState> deriveRelations(List<LikeEventInboxEntity> events) {
        Map<String, LikeRelationState> latest = new LinkedHashMap<>();
        for (LikeEventInboxEntity event : events) {
            LikeTargetType type = LikeTargetType.valueOf(event.getTargetType());
            LikeRelationState state = new LikeRelationState(event.getUserId(), type, event.getTargetId(),
                    Boolean.TRUE.equals(event.getLiked()), event.getEventSequence());
            String key = type + ":" + event.getTargetId() + ":" + event.getUserId();
            latest.merge(key, state, (left, right) -> right.sequence() > left.sequence() ? right : left);
        }
        return List.copyOf(latest.values());
    }

    private List<LikeCountDelta> deriveDeltas(List<LikeEventInboxEntity> events) {
        Map<String, LikeCountDelta> totals = new LinkedHashMap<>();
        for (LikeEventInboxEntity event : events) {
            LikeTargetType type = LikeTargetType.valueOf(event.getTargetType());
            String key = type + ":" + event.getTargetId();
            long value = Boolean.TRUE.equals(event.getLiked()) ? 1L : -1L;
            totals.merge(key, new LikeCountDelta(type, event.getTargetId(), value),
                    (left, right) -> new LikeCountDelta(type, event.getTargetId(), left.delta() + right.delta()));
        }
        return List.copyOf(totals.values());
    }

    private void updateCounts(List<LikeCountDelta> deltas) {
        Map<LikeTargetType, List<LikeCountDelta>> groups = new EnumMap<>(LikeTargetType.class);
        for (LikeCountDelta delta : deltas) {
            if (delta.delta() != 0) groups.computeIfAbsent(delta.targetType(), ignored -> new ArrayList<>()).add(delta);
        }
        groups.forEach((type, items) -> {
            for (int from = 0; from < items.size(); from += 500) {
                List<LikeCountDelta> chunk = items.subList(from, Math.min(from + 500, items.size()));
                switch (type) {
                    case COURSE -> countMapper.updateCourseCounts(chunk);
                    case QUESTION -> countMapper.updateQuestionCounts(chunk);
                    case ANSWER -> countMapper.updateAnswerCounts(chunk);
                }
            }
        });
    }

    private void setAbsoluteCount(LikeTargetType type, Long targetId, long count) {
        switch (type) {
            case COURSE -> countMapper.setCourseCount(targetId, count);
            case QUESTION -> countMapper.setQuestionCount(targetId, count);
            case ANSWER -> countMapper.setAnswerCount(targetId, count);
        }
    }
}
