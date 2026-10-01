package com.learnhub.interaction.like;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LikeBatchFlushService {
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = script("lua/like_claim_batch.lua");
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = script("lua/like_complete_batch.lua");

    private final StringRedisTemplate redis;
    private final LikePersistenceService persistence;
    private final LikeEventInboxService inbox;
    private final LikeEventAggregationService aggregation;

    public LikeBatchFlushService(StringRedisTemplate redis, LikePersistenceService persistence,
                                 LikeEventInboxService inbox, LikeEventAggregationService aggregation) {
        this.redis = redis;
        this.persistence = persistence;
        this.inbox = inbox;
        this.aggregation = aggregation;
    }

    public void flush() {
        if (Boolean.TRUE.equals(redis.hasKey(LikeRedisKeys.RECONCILE_LOCK))) return;
        aggregation.aggregate(inbox.pending(500));
        retryProcessingBatches();
        String batchId = UUID.randomUUID().toString();
        Long claimed = redis.execute(CLAIM_SCRIPT, List.of(
                LikeRedisKeys.LIVE_DELTA,
                LikeRedisKeys.LIVE_RELATIONS,
                LikeRedisKeys.LIVE_EVENTS,
                LikeRedisKeys.batchDelta(batchId),
                LikeRedisKeys.batchRelations(batchId),
                LikeRedisKeys.batchEvents(batchId),
                LikeRedisKeys.PROCESSING_BATCHES,
                LikeRedisKeys.RECONCILE_LOCK), batchId);
        if (claimed != null && claimed == 1) process(batchId);
    }

    public void retryProcessingBatches() {
        Set<String> batchIds = redis.opsForSet().members(LikeRedisKeys.PROCESSING_BATCHES);
        if (batchIds == null) return;
        batchIds.forEach(this::process);
    }

    void process(String batchId) {
        Map<Object, Object> relationValues = redis.opsForHash().entries(LikeRedisKeys.batchRelations(batchId));
        Map<Object, Object> deltaValues = redis.opsForHash().entries(LikeRedisKeys.batchDelta(batchId));
        Set<Object> eventKeys = redis.opsForHash().keys(LikeRedisKeys.batchEvents(batchId));
        List<String> eventIds = eventKeys == null ? List.of()
                : eventKeys.stream().map(Object::toString).toList();
        List<LikeRelationState> relations = parseRelations(relationValues);
        List<LikeCountDelta> deltas = parseDeltas(deltaValues);
        if (persistence.applyBatch(batchId, relations, deltas, eventIds)) {
            redis.execute(COMPLETE_SCRIPT, List.of(
                    LikeRedisKeys.batchDelta(batchId),
                    LikeRedisKeys.batchRelations(batchId),
                    LikeRedisKeys.batchEvents(batchId),
                    LikeRedisKeys.PROCESSING_BATCHES), batchId);
        }
    }

    private List<LikeRelationState> parseRelations(Map<Object, Object> values) {
        List<LikeRelationState> states = new ArrayList<>(values.size());
        values.forEach((field, value) -> {
            String[] key = field.toString().split(":", 3);
            String[] state = value.toString().split("\\|", 3);
            if (key.length != 3 || state.length != 3) throw new IllegalStateException("Invalid like relation batch data");
            states.add(new LikeRelationState(Long.valueOf(key[2]), LikeTargetType.valueOf(key[0]),
                    Long.valueOf(key[1]), "1".equals(state[0]), Long.parseLong(state[1])));
        });
        return states;
    }

    private List<LikeCountDelta> parseDeltas(Map<Object, Object> values) {
        List<LikeCountDelta> deltas = new ArrayList<>(values.size());
        values.forEach((field, value) -> {
            String[] key = field.toString().split(":", 2);
            if (key.length != 2) throw new IllegalStateException("Invalid like delta batch data");
            deltas.add(new LikeCountDelta(LikeTargetType.valueOf(key[0]), Long.valueOf(key[1]),
                    Long.parseLong(value.toString())));
        });
        return deltas;
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
