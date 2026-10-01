package com.learnhub.interaction.like;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LikeEventAggregationService {
    private static final DefaultRedisScript<Long> AGGREGATE_SCRIPT = new DefaultRedisScript<>();

    static {
        AGGREGATE_SCRIPT.setLocation(new ClassPathResource("lua/like_aggregate_batch.lua"));
        AGGREGATE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final LikeEventInboxService inbox;

    public LikeEventAggregationService(StringRedisTemplate redis, LikeEventInboxService inbox) {
        this.redis = redis;
        this.inbox = inbox;
    }

    public long aggregate(List<LikeEvent> events) {
        if (events.isEmpty()) return 0;
        for (LikeEvent event : events) validate(event);
        events = inbox.persistAndGetPending(events);
        if (events.isEmpty()) return 0;
        List<String> keys = new ArrayList<>(events.size() + 4);
        keys.add(LikeRedisKeys.LIVE_DELTA);
        keys.add(LikeRedisKeys.LIVE_RELATIONS);
        keys.add(LikeRedisKeys.PENDING_PUBLISH);
        keys.add(LikeRedisKeys.LIVE_EVENTS);
        keys.add(LikeRedisKeys.RECONCILE_LOCK);
        List<String> args = new ArrayList<>(events.size() * 5);
        for (LikeEvent event : events) {
            keys.add(LikeRedisKeys.eventDedup(event.eventId()));
            args.add(LikeRedisKeys.targetField(event.targetType(), event.targetId()));
            args.add(LikeRedisKeys.relationField(event));
            args.add(event.liked() ? "1" : "-1");
            args.add((event.liked() ? "1" : "0") + "|" + event.sequence() + "|" + event.eventId());
            args.add(event.eventId());
        }
        Long processed = redis.execute(AGGREGATE_SCRIPT, keys, args.toArray());
        if (processed == null) throw new IllegalStateException("Redis did not aggregate like events");
        if (processed == -1) throw new IllegalStateException("Like reconciliation is running");
        return processed;
    }

    private void validate(LikeEvent event) {
        if (event.version() != 1) throw new IllegalArgumentException("Unsupported like event version");
        if (event.eventId() == null || event.eventId().isBlank() || event.sequence() <= 0
                || event.userId() == null || event.targetType() == null || event.targetId() == null
                || event.occurredAt() == null) {
            throw new IllegalArgumentException("Invalid like event");
        }
    }
}
