package com.learnhub.interaction.like;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class LikeEventConsumer {
    private final StringRedisTemplate redis;

    public LikeEventConsumer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @RabbitListener(queues = "learnhub.like.events")
    public void consume(LikeEvent event) {
        String key = "learnhub:like:delta:" + event.targetType().name().toLowerCase();
        redis.opsForHash().increment(key, event.targetId().toString(), event.liked() ? 1 : -1);
    }
}
