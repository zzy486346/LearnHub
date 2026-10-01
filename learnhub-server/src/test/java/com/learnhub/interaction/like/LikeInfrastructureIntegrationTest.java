package com.learnhub.interaction.like;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/learnhub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=learnhub",
        "spring.datasource.password=learnhub",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=learnhub",
        "spring.rabbitmq.password=learnhub",
        "learnhub.rabbit.enabled=true",
        "learnhub.like.flush-enabled=false",
        "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false",
        "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.storage.oss.enabled=false"
})
@EnabledIfSystemProperty(named = "learnhub.it.enabled", matches = "true")
class LikeInfrastructureIntegrationTest {
    @Autowired private LikeService likeService;
    @Autowired private LikeBatchFlushService flushService;
    @Autowired private LikePersistenceService persistence;
    @Autowired private LikeReconciliationService reconciliation;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private JdbcTemplate jdbc;

    private final long courseId = 8_900_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
    private final long userId = 8_800_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
    private Set<String> existingBatchIds;
    private String flushedBatchId;

    @BeforeEach
    void setUp() {
        clearRedisState();
        existingBatchIds = Set.copyOf(jdbc.queryForList("SELECT batch_id FROM like_sync_batch", String.class));
        jdbc.update("INSERT INTO course (id, category_id, title, instructor, status, like_count) VALUES (?, 1, ?, 'it', 'PUBLISHED', 0)",
                courseId, "like-it-" + courseId);
    }

    @AfterEach
    void cleanUp() {
        clearRedisState();
        jdbc.update("DELETE FROM like_record WHERE target_type = 'COURSE' AND target_id = ?", courseId);
        jdbc.update("DELETE FROM like_event_inbox WHERE target_type = 'COURSE' AND target_id = ?", courseId);
        jdbc.queryForList("SELECT batch_id FROM like_sync_batch", String.class).stream()
                .filter(batchId -> !existingBatchIds.contains(batchId))
                .forEach(batchId -> jdbc.update("DELETE FROM like_sync_batch WHERE batch_id = ?", batchId));
        jdbc.update("DELETE FROM course WHERE id = ?", courseId);
    }

    @Test
    void duplicateLikeAndCancelRequestsFlushAsynchronouslyAndBatchReplayIsIdempotent() throws Exception {
        assertEquals(new LikeResult(true, 1), likeService.setLike(userId, LikeTargetType.COURSE, courseId, true));
        assertEquals(new LikeResult(true, 1), likeService.setLike(userId, LikeTargetType.COURSE, courseId, true));
        assertEquals(new LikeResult(false, 0), likeService.setLike(userId, LikeTargetType.COURSE, courseId, false));
        assertEquals(new LikeResult(false, 0), likeService.setLike(userId, LikeTargetType.COURSE, courseId, false));
        assertEquals(new LikeResult(true, 1), likeService.setLike(userId, LikeTargetType.COURSE, courseId, true));
        assertEquals(0L, databaseCount());

        awaitAggregatedDelta("1");
        Set<String> volatileKeys = redis.keys("learnhub:like:{likes}:*");
        if (volatileKeys != null && !volatileKeys.isEmpty()) redis.delete(volatileKeys);
        flushService.flush();

        assertEquals(1L, databaseCount());
        assertTrue(recordActive());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM like_event_inbox WHERE target_type = 'COURSE' AND target_id = ? AND status = 'PENDING'",
                Integer.class, courseId));
        assertTrue(reconciliation.reconcileIfIdle());
        assertEquals(new LikeResult(false, 0), likeService.setLike(userId, LikeTargetType.COURSE, courseId, false));
        awaitAggregatedDelta("-1");
        flushService.flush();
        assertEquals(0L, databaseCount());
        assertFalse(recordActive());
        flushedBatchId = jdbc.queryForList(
                        "SELECT batch_id FROM like_sync_batch WHERE status = 'SUCCESS' ORDER BY created_at DESC",
                        String.class).stream()
                .filter(batchId -> !existingBatchIds.contains(batchId))
                .findFirst()
                .orElseThrow();
        assertTrue(persistence.applyBatch(flushedBatchId,
                List.of(new LikeRelationState(userId, LikeTargetType.COURSE, courseId, true, Long.MAX_VALUE)),
                List.of(new LikeCountDelta(LikeTargetType.COURSE, courseId, 1)), List.of()));
        assertEquals(0L, databaseCount());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(LikeRedisKeys.batchDelta(flushedBatchId))));
    }

    @Test
    void claimRefusesSecondBatchWhileAnotherBatchIsProcessing() {
        redis.opsForHash().put(LikeRedisKeys.LIVE_DELTA, "COURSE:" + courseId, "1");
        redis.opsForSet().add(LikeRedisKeys.PROCESSING_BATCHES, "existing-batch");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/like_claim_batch.lua"));
        script.setResultType(Long.class);

        Long claimed = redis.execute(script, List.of(
                LikeRedisKeys.LIVE_DELTA,
                LikeRedisKeys.LIVE_RELATIONS,
                LikeRedisKeys.LIVE_EVENTS,
                LikeRedisKeys.batchDelta("new-batch"),
                LikeRedisKeys.batchRelations("new-batch"),
                LikeRedisKeys.batchEvents("new-batch"),
                LikeRedisKeys.PROCESSING_BATCHES,
                LikeRedisKeys.RECONCILE_LOCK), "new-batch");

        assertEquals(0L, claimed);
        assertEquals("1", redis.opsForHash().get(LikeRedisKeys.LIVE_DELTA, "COURSE:" + courseId));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(LikeRedisKeys.batchDelta("new-batch"))));
    }

    private void awaitAggregatedDelta(String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Object delta = redis.opsForHash().get(LikeRedisKeys.LIVE_DELTA,
                    LikeRedisKeys.targetField(LikeTargetType.COURSE, courseId));
            if (expected.equals(delta)) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for RabbitMQ like events to aggregate in Redis");
    }

    private long databaseCount() {
        Long count = jdbc.queryForObject("SELECT like_count FROM course WHERE id = ?", Long.class, courseId);
        return count == null ? -1 : count;
    }

    private boolean recordActive() {
        Integer active = jdbc.queryForObject(
                "SELECT active FROM like_record WHERE user_id = ? AND target_type = 'COURSE' AND target_id = ?",
                Integer.class, userId, courseId);
        return active != null && active == 1;
    }

    private void clearRedisState() {
        Set<String> keys = redis.keys("learnhub:like:{likes}:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        rabbit.execute(channel -> {
            channel.queuePurge(LikeService.QUEUE);
            return null;
        });
    }
}
