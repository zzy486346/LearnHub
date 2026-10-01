package com.learnhub.interaction.like;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.like.publish-retry-enabled", havingValue = "true", matchIfMissing = true)
public class LikePublishRetryJob {
    private final LikeService service;

    public LikePublishRetryJob(LikeService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${learnhub.like.publish-retry-delay:5000}")
    public void republish() {
        service.republishPending(100);
    }
}
