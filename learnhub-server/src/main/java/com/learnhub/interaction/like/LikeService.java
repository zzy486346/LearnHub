package com.learnhub.interaction.like;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LikeService {
    public static final String EXCHANGE = "learnhub.interaction";
    public static final String ROUTING_KEY = "like.changed.v2";
    public static final String QUEUE = "learnhub.like.events.v2";
    public static final String DEAD_LETTER_EXCHANGE = "learnhub.interaction.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "like.changed.v2.dead";
    public static final String DEAD_LETTER_QUEUE = "learnhub.like.events.v2.dlq";
    private static final DefaultRedisScript<Long> TOGGLE_SCRIPT = new DefaultRedisScript<>();

    static {
        TOGGLE_SCRIPT.setLocation(new ClassPathResource("lua/like_toggle.lua"));
        TOGGLE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final LikeEventAggregationService aggregationService;
    private final ObjectMapper objectMapper;
    private final boolean rabbitEnabled;
    private final Duration publisherConfirmTimeout;
    private final LikeSequenceService sequences;

    public LikeService(ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectProvider<RabbitTemplate> rabbitProvider,
                       LikeEventAggregationService aggregationService,
                       ObjectMapper objectMapper,
                       @Value("${learnhub.rabbit.enabled:false}") boolean rabbitEnabled,
                       @Value("${learnhub.like.publisher-confirm-timeout:10s}") Duration publisherConfirmTimeout,
                       LikeSequenceService sequences) {
        this.redis = redisProvider.getIfAvailable();
        this.rabbit = rabbitProvider.getIfAvailable();
        this.aggregationService = aggregationService;
        this.objectMapper = objectMapper;
        this.rabbitEnabled = rabbitEnabled;
        this.publisherConfirmTimeout = publisherConfirmTimeout;
        this.sequences = sequences;
    }

    public LikeResult setLike(Long userId, LikeTargetType type, Long targetId, boolean liked) {
        requireRedis();
        long sequence = sequences.next();
        LikeEvent event = new LikeEvent(UUID.randomUUID().toString(), userId, type, targetId,
                liked, sequence, Instant.now(), 1);
        String serialized = serialize(event);
        Long changed = redis.execute(TOGGLE_SCRIPT, List.of(
                        LikeRedisKeys.userSet(type, targetId),
                        LikeRedisKeys.PENDING_PUBLISH,
                        LikeRedisKeys.TARGETS,
                        LikeRedisKeys.RECONCILE_LOCK),
                userId.toString(), liked ? "1" : "0", event.eventId(), serialized,
                LikeRedisKeys.targetField(type, targetId));
        if (changed == null) throw new IllegalStateException("Redis did not apply like state");
        if (changed == -1) throw new IllegalStateException("Like reconciliation is running; retry shortly");
        if (changed == 1) publish(event);
        Long size = redis.opsForSet().size(LikeRedisKeys.userSet(type, targetId));
        return new LikeResult(liked, size == null ? 0 : size);
    }

    public LikeResult status(Long userId, LikeTargetType type, Long targetId) {
        requireRedis();
        String key = LikeRedisKeys.userSet(type, targetId);
        Boolean member = redis.opsForSet().isMember(key, userId.toString());
        Long size = redis.opsForSet().size(key);
        return new LikeResult(Boolean.TRUE.equals(member), size == null ? 0 : size);
    }

    public long count(LikeTargetType type, Long targetId) {
        requireRedis();
        Long size = redis.opsForSet().size(LikeRedisKeys.userSet(type, targetId));
        return size == null ? 0 : size;
    }

    public void republishPending(int limit) {
        requireRedis();
        int published = 0;
        try (Cursor<Map.Entry<Object, Object>> cursor = redis.opsForHash().scan(
                LikeRedisKeys.PENDING_PUBLISH, ScanOptions.scanOptions().count(limit).build())) {
            while (cursor.hasNext() && published++ < limit) {
                try {
                    publish(deserialize(cursor.next().getValue().toString()));
                } catch (IllegalStateException ignored) {
                    // Keep the pending event for a later bounded retry; continue past malformed or unavailable events.
                }
            }
        }
    }

    void publish(LikeEvent event) {
        if (!rabbitEnabled) {
            aggregationService.aggregate(List.of(event));
            return;
        }
        if (rabbit == null) throw new IllegalStateException("RabbitMQ unavailable; like event was not accepted");
        CorrelationData correlation = new CorrelationData(event.eventId());
        try {
            rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, event, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    publisherConfirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ rejected like event: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable like event");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming like event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Like event was not confirmed and remains pending", ex);
        }
    }

    private void requireRedis() {
        if (redis == null) throw new IllegalStateException("Redis unavailable; likes are temporarily unavailable");
    }

    private String serialize(LikeEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize like event", ex);
        }
    }

    private LikeEvent deserialize(String json) {
        try {
            return objectMapper.readValue(json, LikeEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot deserialize like event", ex);
        }
    }
}
