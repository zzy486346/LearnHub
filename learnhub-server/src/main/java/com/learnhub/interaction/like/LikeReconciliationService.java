package com.learnhub.interaction.like;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LikeReconciliationService {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>();
    static {
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock_if_owned.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
        RENEW_SCRIPT.setLocation(new ClassPathResource("lua/renew_if_owned.lua"));
        RENEW_SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final LikePersistenceService persistence;
    private final LikeSequenceService sequences;

    public LikeReconciliationService(StringRedisTemplate redis, LikePersistenceService persistence,
                                     LikeSequenceService sequences) {
        this.redis = redis;
        this.persistence = persistence;
        this.sequences = sequences;
    }

    public boolean reconcileIfIdle() {
        String owner = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(LikeRedisKeys.RECONCILE_LOCK, owner, Duration.ofMinutes(2));
        if (!Boolean.TRUE.equals(locked)) return false;
        try {
            if (hasPendingWork()) return false;
            reconcileAll(owner);
            return true;
        } finally {
            redis.execute(UNLOCK_SCRIPT, List.of(LikeRedisKeys.RECONCILE_LOCK), owner);
        }
    }

    private void reconcileAll(String owner) {
        List<LikeRecordEntity> records = persistence.listAllRecords();
        Set<String> targets = new HashSet<>();
        Set<String> redisTargets = redis.opsForSet().members(LikeRedisKeys.TARGETS);
        if (redisTargets != null) targets.addAll(redisTargets);
        records.forEach(record -> targets.add(record.getTargetType() + ":" + record.getTargetId()));

        for (String target : targets) {
            Long renewed = redis.execute(RENEW_SCRIPT, List.of(LikeRedisKeys.RECONCILE_LOCK), owner, "120000");
            if (renewed == null || renewed != 1) throw new IllegalStateException("Like reconciliation lease was lost");
            Target parsed = parseTarget(target);
            String userSet = LikeRedisKeys.userSet(parsed.type(), parsed.id());
            if (!Boolean.TRUE.equals(redis.hasKey(userSet))) {
                records.stream()
                        .filter(record -> record.getTargetType().equals(parsed.type().name()))
                        .filter(record -> record.getTargetId().equals(parsed.id()))
                        .filter(record -> Boolean.TRUE.equals(record.getActive()))
                        .forEach(record -> redis.opsForSet().add(userSet, record.getUserId().toString()));
            }
            Set<String> members = redis.opsForSet().members(userSet);
            List<Long> activeUsers = members == null ? List.of()
                    : members.stream().map(Long::valueOf).toList();
            persistence.reconcileTarget(parsed.type(), parsed.id(), activeUsers, sequences.next());
            redis.opsForSet().add(LikeRedisKeys.TARGETS, target);
        }
    }

    private boolean hasPendingWork() {
        Long pendingPublish = redis.opsForHash().size(LikeRedisKeys.PENDING_PUBLISH);
        Long liveDelta = redis.opsForHash().size(LikeRedisKeys.LIVE_DELTA);
        Long liveRelations = redis.opsForHash().size(LikeRedisKeys.LIVE_RELATIONS);
        Long liveEvents = redis.opsForHash().size(LikeRedisKeys.LIVE_EVENTS);
        Long processing = redis.opsForSet().size(LikeRedisKeys.PROCESSING_BATCHES);
        return positive(pendingPublish) || positive(liveDelta) || positive(liveRelations)
                || positive(liveEvents) || positive(processing);
    }

    private boolean positive(Long value) { return value != null && value > 0; }

    private Target parseTarget(String target) {
        String[] parts = target.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Invalid like target: " + target);
        return new Target(LikeTargetType.valueOf(parts[0]), Long.valueOf(parts[1]));
    }

    private record Target(LikeTargetType type, Long id) {}
}
