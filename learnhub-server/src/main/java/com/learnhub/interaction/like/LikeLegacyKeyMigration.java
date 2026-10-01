package com.learnhub.interaction.like;

import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "learnhub.like.legacy-key-migration-enabled", havingValue = "true", matchIfMissing = true)
public class LikeLegacyKeyMigration implements ApplicationRunner {
    private static final String PREFIX = "learnhub:likes:";
    private final StringRedisTemplate redis;

    public LikeLegacyKeyMigration(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys == null) return;
        for (String oldKey : keys) migrate(oldKey);
    }

    private void migrate(String oldKey) {
        String[] parts = oldKey.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) return;
        LikeTargetType type;
        long targetId;
        try {
            type = LikeTargetType.valueOf(parts[0].toUpperCase());
            targetId = Long.parseLong(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        Set<String> members = redis.opsForSet().members(oldKey);
        if (members != null && !members.isEmpty()) {
            redis.opsForSet().add(LikeRedisKeys.userSet(type, targetId), members.toArray(String[]::new));
        }
        redis.opsForSet().add(LikeRedisKeys.TARGETS, LikeRedisKeys.targetField(type, targetId));
    }
}
