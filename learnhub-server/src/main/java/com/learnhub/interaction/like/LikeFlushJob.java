package com.learnhub.interaction.like;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.like.flush-enabled", havingValue = "true", matchIfMissing = true)
public class LikeFlushJob {
    private final LikeBatchFlushService flushService;

    public LikeFlushJob(LikeBatchFlushService flushService) {
        this.flushService = flushService;
    }

    @Scheduled(fixedDelayString = "${learnhub.like.flush-delay:5000}")
    public void flush() {
        flushService.flush();
    }
}
