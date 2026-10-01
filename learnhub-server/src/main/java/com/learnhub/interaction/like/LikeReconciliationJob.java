package com.learnhub.interaction.like;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.like.reconciliation-enabled", havingValue = "true", matchIfMissing = true)
public class LikeReconciliationJob {
    private final LikeReconciliationService service;

    public LikeReconciliationJob(LikeReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${learnhub.like.reconciliation-delay:3600000}")
    public void reconcile() {
        service.reconcileIfIdle();
    }
}
