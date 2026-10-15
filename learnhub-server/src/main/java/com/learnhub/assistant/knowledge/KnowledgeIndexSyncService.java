package com.learnhub.assistant.knowledge;

import java.sql.PreparedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexSyncService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexSyncService.class);
    private final KnowledgeStore store;
    private final KnowledgeSearchGateway gateway;
    private final JdbcTemplate jdbc;

    public KnowledgeIndexSyncService(KnowledgeStore store, KnowledgeSearchGateway gateway, JdbcTemplate jdbc) {
        this.store = store; this.gateway = gateway; this.jdbc = jdbc;
    }

    public int syncDue(int limit) {
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            String lock = "lh:knowledge:" + Integer.toUnsignedString(gateway.aliasName().hashCode());
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, lock);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) return 0;
                }
            }
            try {
                if (gateway.ensureAlias()) store.requeueAll();
                int completed = 0;
                for (var document : store.pending(limit)) {
                    try {
                        var chunks = store.chunks(document.id(), document.sourceVersion());
                        if (chunks.isEmpty()) throw new IllegalStateException("材料缺少知识块");
                        for (var chunk : chunks) gateway.index(document, chunk);
                        gateway.removeOtherVersions(document.id(), document.sourceVersion());
                        gateway.refresh();
                        if (store.markIndexed(document.id(), document.sourceVersion())) completed++;
                    } catch (RuntimeException error) {
                        store.markFailed(document.id(), document.sourceVersion());
                        // 不记录异常正文，ES 错误可能包含被拒绝的材料片段。
                        log.warn("知识索引同步失败 documentId={} version={} errorType={}",
                                document.id(), document.sourceVersion(), error.getClass().getSimpleName());
                    }
                }
                return completed;
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, lock); release.execute();
                }
            }
        });
    }
}
