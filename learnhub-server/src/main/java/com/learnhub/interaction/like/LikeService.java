package com.learnhub.interaction.like;

import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LikeService {
    public static final String EXCHANGE = "learnhub.interaction";
    public static final String ROUTING_KEY = "like.changed";

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final CourseMapper courseMapper;
    private final boolean rabbitEnabled;
    private final ConcurrentHashMap<String, Set<String>> fallback = new ConcurrentHashMap<>();

    public LikeService(ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectProvider<RabbitTemplate> rabbitProvider,
                       CourseMapper courseMapper,
                       @Value("${learnhub.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.redis = redisProvider.getIfAvailable();
        this.rabbit = rabbitProvider.getIfAvailable();
        this.courseMapper = courseMapper;
        this.rabbitEnabled = rabbitEnabled;
    }

    public LikeResult setLike(Long userId, LikeTargetType type, Long targetId, boolean liked) {
        String key = key(type, targetId);
        boolean changed;
        long count;
        try {
            if (redis == null) throw new IllegalStateException("Redis unavailable");
            changed = liked
                    ? Boolean.TRUE.equals(redis.opsForSet().add(key, userId.toString()) == 1)
                    : Boolean.TRUE.equals(redis.opsForSet().remove(key, userId.toString()) == 1);
            Long size = redis.opsForSet().size(key);
            count = size == null ? 0 : size;
        } catch (RuntimeException ex) {
            Set<String> users = fallback.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
            changed = liked ? users.add(userId.toString()) : users.remove(userId.toString());
            count = users.size();
        }
        if (changed && rabbitEnabled && rabbit != null) {
            try {
                rabbit.convertAndSend(EXCHANGE, ROUTING_KEY,
                        new LikeEvent(userId, type, targetId, liked, Instant.now()));
            } catch (RuntimeException ignored) {
                // The Redis Set remains the source of truth; MQ publishing can be retried by an outbox in a later version.
            }
        }
        synchronizeCourseCount(type, targetId, count);
        return new LikeResult(liked, count);
    }

    public LikeResult status(Long userId, LikeTargetType type, Long targetId) {
        String key = key(type, targetId);
        try {
            if (redis == null) throw new IllegalStateException("Redis unavailable");
            Boolean member = redis.opsForSet().isMember(key, userId.toString());
            Long size = redis.opsForSet().size(key);
            return new LikeResult(Boolean.TRUE.equals(member), size == null ? 0 : size);
        } catch (RuntimeException ex) {
            Set<String> users = fallback.getOrDefault(key, Set.of());
            return new LikeResult(users.contains(userId.toString()), users.size());
        }
    }

    public long count(LikeTargetType type, Long targetId) {
        String key = key(type, targetId);
        try {
            if (redis == null) throw new IllegalStateException("Redis unavailable");
            Long size = redis.opsForSet().size(key);
            return size == null ? 0 : size;
        } catch (RuntimeException ex) {
            return fallback.getOrDefault(key, Set.of()).size();
        }
    }

    private void synchronizeCourseCount(LikeTargetType type, Long targetId, long count) {
        if (type != LikeTargetType.COURSE) return;
        Course course = new Course();
        course.setId(targetId);
        course.setLikeCount(count);
        courseMapper.updateById(course);
    }

    private String key(LikeTargetType type, Long targetId) {
        return "learnhub:likes:" + type.name().toLowerCase() + ":" + targetId;
    }
}
