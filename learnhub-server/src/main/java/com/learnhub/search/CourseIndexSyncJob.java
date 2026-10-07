package com.learnhub.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CourseIndexSyncJob {
    private static final Logger log = LoggerFactory.getLogger(CourseIndexSyncJob.class);
    private final CourseIndexSyncService service;
    private final boolean enabled;
    private final int batchSize;

    public CourseIndexSyncJob(CourseIndexSyncService service,
                              @Value("${learnhub.search.sync-enabled:true}") boolean enabled,
                              @Value("${learnhub.search.sync-batch-size:100}") int batchSize) {
        this.service = service;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${learnhub.search.sync-delay:5000}")
    public void sync() {
        if (!enabled) return;
        try {
            service.syncDue(batchSize);
        } catch (RuntimeException error) {
            log.warn("课程搜索索引定时同步失败", error);
        }
    }
}
