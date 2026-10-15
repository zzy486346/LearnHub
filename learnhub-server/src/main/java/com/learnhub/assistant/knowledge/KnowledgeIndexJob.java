package com.learnhub.assistant.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIndexJob {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexJob.class);
    private final KnowledgeIndexSyncService sync;
    private final boolean enabled;
    public KnowledgeIndexJob(KnowledgeIndexSyncService sync,
            @Value("${learnhub.knowledge.sync-enabled:false}") boolean enabled) {
        this.sync = sync; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${learnhub.knowledge.sync-delay:5000}")
    public void run() {
        if (!enabled) return;
        try { sync.syncDue(20); }
        catch (RuntimeException error) {
            log.warn("知识索引补偿暂不可用 errorType={}", error.getClass().getSimpleName());
        }
    }
}
