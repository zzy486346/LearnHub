package com.learnhub.interaction.like;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LikeSequenceService implements ApplicationRunner {
    private static final DefaultRedisScript<Long> NEXT_SCRIPT = script("lua/like_next_sequence.lua");
    private static final DefaultRedisScript<Long> FLOOR_SCRIPT = script("lua/like_sequence_floor.lua");

    private final StringRedisTemplate redis;
    private final LikeRecordMapper records;
    private final LikeEventInboxService inbox;

    public LikeSequenceService(StringRedisTemplate redis, LikeRecordMapper records, LikeEventInboxService inbox) {
        this.redis = redis;
        this.records = records;
        this.inbox = inbox;
    }

    public long next() {
        if (!Boolean.TRUE.equals(redis.hasKey(LikeRedisKeys.SEQUENCE))) initializeFloor();
        Long value = redis.execute(NEXT_SCRIPT, List.of(LikeRedisKeys.SEQUENCE));
        if (value == null) throw new IllegalStateException("Redis did not allocate like sequence");
        return value;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializeFloor();
    }

    private void initializeFloor() {
        long floor = Math.max(records.maxEventSequence(), inbox.maxSequence());
        redis.execute(FLOOR_SCRIPT, List.of(LikeRedisKeys.SEQUENCE), Long.toString(floor));
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
