package com.learnhub.assistant.knowledge.vector;

import com.learnhub.assistant.knowledge.KnowledgeDocumentRecord;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

/** MySQL 保存同步进度，PG 只保存可重建投影；不主动调度，不持有 PG 数据源。 */
@Service
public class VectorProjectionSync {
    private static final Logger log = LoggerFactory.getLogger(VectorProjectionSync.class);
    private final JdbcTemplate jdbc;
    private final KnowledgeStore knowledge;

    public VectorProjectionSync(JdbcTemplate jdbc, KnowledgeStore knowledge) {
        this.jdbc = new com.learnhub.assistant.runtime.DeadlineJdbcTemplate(jdbc.getDataSource());
        this.knowledge = knowledge;
    }

    /** 成功完成或删除的当前代次数；并发任务拿不到专用锁时返回零。 */
    public int syncDue(CourseVectorIndex index, int limit) {
        Objects.requireNonNull(index, "index");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        Integer completed = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Vector synchronization must run outside a transaction");
            JdbcTemplate work = connectionJdbc(connection);
            String lock = lockName(work);
            Integer acquired = work.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lock);
            if (!Integer.valueOf(1).equals(acquired)) return 0;
            try {
                discover(work, boundedLimit);
                List<Generation> due = work.query("""
                        SELECT d.id, d.source_version, d.course_id, d.lesson_id, d.title, d.deleted, p.attempts, p.status
                        FROM knowledge_vector_projection p
                        JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                        WHERE p.model_id = ? AND (p.status IN ('PENDING', 'FAILED')
                            OR (p.status = 'INDEXED' AND p.cleanup_pending = 1))
                          AND p.next_retry_at <= CURRENT_TIMESTAMP(3)
                          AND (d.deleted = 1 OR d.indexed_version = d.source_version)
                        ORDER BY p.next_retry_at, p.id LIMIT ?
                        """, (rs, row) -> new Generation(new KnowledgeDocumentRecord(rs.getLong("id"),
                        rs.getLong("source_version"), rs.getLong("course_id"), rs.getLong("lesson_id"),
                        rs.getString("title")), rs.getBoolean("deleted"), rs.getInt("attempts"),
                        "INDEXED".equals(rs.getString("status"))),
                        BgeChineseEmbedding.MODEL_ID, boundedLimit);
                int count = 0;
                for (Generation generation : due) {
                    try {
                        if (generation.deleted()) {
                            if (removeDeleted(connection, work, index, generation.document())) count++;
                        } else if (generation.indexed()) {
                            if (cleanup(work, index, generation.document())) count++;
                        } else if (project(work, index, generation.document())) count++;
                    } catch (RuntimeException failure) {
                        markFailed(work, generation);
                        // 供应商/SQL 异常消息可能包含材料正文；只记录类型和服务端编号。
                        log.warn("向量投影同步失败 documentId={} version={} errorType={}",
                                generation.document().id(), generation.document().sourceVersion(),
                                failure.getClass().getSimpleName());
                    }
                }
                return count;
            } finally {
                work.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
            }
        });
        return completed == null ? 0 : completed;
    }

    /** 仅允许可见且材料和向量均已同步的当前代次参与 PG 召回。超大范围必须整体回退 BM25。 */
    public Map<Long, Long> currentVersions(long courseId, List<Long> authorizedLessonIds) {
        if (courseId <= 0 || authorizedLessonIds == null || authorizedLessonIds.size() > 10_000
                || authorizedLessonIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid authorized vector scope");
        }
        if (authorizedLessonIds.isEmpty()) return Map.of();
        var named = new NamedParameterJdbcTemplate(jdbc);
        List<Map.Entry<Long, Long>> versions = named.query("""
                SELECT d.id, d.source_version FROM knowledge_document d
                JOIN knowledge_vector_projection p ON p.document_id = d.id AND p.source_version = d.source_version
                WHERE d.course_id = :course AND d.lesson_id IN (:lessons) AND d.deleted = 0
                  AND d.indexed_version = d.source_version AND p.model_id = :model AND p.status = 'INDEXED'
                ORDER BY d.id LIMIT 1001
                """, Map.of("course", courseId, "lessons", authorizedLessonIds,
                "model", BgeChineseEmbedding.MODEL_ID),
                (rs, row) -> Map.entry(rs.getLong("id"), rs.getLong("source_version")));
        if (versions.size() > 1000) throw new IllegalStateException("Vector document scope exceeds 1000");
        Map<Long, Long> result = new LinkedHashMap<>();
        versions.forEach(version -> result.put(version.getKey(), version.getValue()));
        return Map.copyOf(result);
    }

    /** 只重置当前模型的当前代次同步进度，不改变材料、权限或其他模型投影。 */
    public int requeueAll() {
        Integer changed = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Vector rebuild must run outside a transaction");
            JdbcTemplate work = connectionJdbc(connection);
            String lock = lockName(work);
            Integer acquired = work.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lock);
            if (!Integer.valueOf(1).equals(acquired)) throw new IllegalStateException("Vector synchronization is busy");
            try {
                return work.update("""
                        UPDATE knowledge_vector_projection p
                        JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                        SET p.status = 'PENDING', p.cleanup_pending = 1, p.attempts = 0, p.next_retry_at = CURRENT_TIMESTAMP(3),
                            p.last_error = NULL, p.updated_at = CURRENT_TIMESTAMP(3)
                        WHERE p.model_id = ?
                        """, BgeChineseEmbedding.MODEL_ID);
            } finally {
                work.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
            }
        });
        return changed == null ? 0 : changed;
    }

    private void discover(JdbcTemplate work, int limit) {
        work.update("""
                INSERT INTO knowledge_vector_projection
                    (document_id, source_version, model_id, status, attempts, next_retry_at)
                SELECT d.id, d.source_version, ?, 'PENDING', 0, CURRENT_TIMESTAMP(3)
                FROM knowledge_document d
                WHERE NOT EXISTS (SELECT 1 FROM knowledge_vector_projection p
                    WHERE p.document_id = d.id AND p.source_version = d.source_version AND p.model_id = ?)
                ORDER BY d.id LIMIT ?
                """, BgeChineseEmbedding.MODEL_ID, BgeChineseEmbedding.MODEL_ID, limit);
        // 逻辑删除通常不递增来源版本：已同步代次也必须重新进入删除补偿。
        work.update("""
                UPDATE knowledge_vector_projection p
                JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                SET p.status = 'PENDING', p.cleanup_pending = 1, p.attempts = 0, p.next_retry_at = CURRENT_TIMESTAMP(3),
                    p.last_error = NULL, p.updated_at = CURRENT_TIMESTAMP(3)
                WHERE p.model_id = ? AND p.status = 'INDEXED' AND d.deleted = 1
                """, BgeChineseEmbedding.MODEL_ID);
    }

    private boolean project(JdbcTemplate work, CourseVectorIndex index, KnowledgeDocumentRecord document) {
        if (!current(work, document, false)) return false;
        var chunks = knowledge.chunks(document.id(), document.sourceVersion());
        if (chunks.isEmpty()) throw new IllegalStateException("Knowledge generation has no chunks");
        for (var chunk : chunks) {
            if (!current(work, document, false)) return false;
            index.upsert(document, chunk);
        }
        // 最后一次写入后再复核，并用同条 UPDATE 的事实源条件防止复核与完成之间的竞态。
        if (!current(work, document, false)) return false;
        if (mark(work, document, "INDEXED", false) == 0) return false;
        return cleanup(work, index, document);
    }

    private boolean cleanup(JdbcTemplate work, CourseVectorIndex index, KnowledgeDocumentRecord document) {
        if (!current(work, document, false)) return false;
        index.removeOlderVersions(document.id(), document.sourceVersion());
        // INDEXED 已提交后进程崩溃仍保留 cleanup_pending，下一轮继续旧代次清理。
        return work.update("""
                UPDATE knowledge_vector_projection p
                JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                SET p.cleanup_pending = 0, p.updated_at = CURRENT_TIMESTAMP(3)
                WHERE p.document_id = ? AND p.source_version = ? AND p.model_id = ?
                  AND p.status = 'INDEXED' AND d.deleted = 0 AND d.indexed_version = d.source_version
                """, document.id(), document.sourceVersion(), BgeChineseEmbedding.MODEL_ID) == 1;
    }

    private boolean removeDeleted(Connection connection, JdbcTemplate work, CourseVectorIndex index,
                                  KnowledgeDocumentRecord document) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        // GET_LOCK 只串行化投影任务；行锁还阻止同文档版本变更越过 PG 删除操作。
        if (!autoCommit) throw new IllegalStateException("Vector synchronization must run outside a transaction");
        connection.setAutoCommit(false);
        try {
            List<Long> locked = work.query("""
                    SELECT id FROM knowledge_document WHERE id = ? AND source_version = ? AND deleted = 1 FOR UPDATE
                    """, (rs, row) -> rs.getLong(1), document.id(), document.sourceVersion());
            if (locked.isEmpty()) { connection.rollback(); return false; }
            index.removeDocument(document.id());
            boolean removed = mark(work, document, "REMOVED", true) == 1;
            connection.commit();
            return removed;
        } catch (RuntimeException | SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private boolean current(JdbcTemplate work, KnowledgeDocumentRecord document, boolean deleted) {
        Integer count = work.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document
                WHERE id = ? AND source_version = ? AND deleted = ?
                  AND (deleted = 1 OR indexed_version = source_version)
                """, Integer.class, document.id(), document.sourceVersion(), deleted ? 1 : 0);
        return Integer.valueOf(1).equals(count);
    }

    private int mark(JdbcTemplate work, KnowledgeDocumentRecord document, String status, boolean deleted) {
        return work.update("""
                UPDATE knowledge_vector_projection p
                JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                SET p.status = ?, p.cleanup_pending = ?, p.last_error = NULL, p.updated_at = CURRENT_TIMESTAMP(3)
                WHERE p.document_id = ? AND p.source_version = ? AND p.model_id = ?
                  AND p.status IN ('PENDING', 'FAILED') AND d.deleted = ?
                  AND (d.deleted = 1 OR d.indexed_version = d.source_version)
                """, status, deleted ? 0 : 1, document.id(), document.sourceVersion(), BgeChineseEmbedding.MODEL_ID,
                deleted ? 1 : 0);
    }

    private void markFailed(JdbcTemplate work, Generation generation) {
        work.update("""
                UPDATE knowledge_vector_projection p
                JOIN knowledge_document d ON d.id = p.document_id AND d.source_version = p.source_version
                SET p.status = 'FAILED', p.attempts = LEAST(p.attempts + 1, 30),
                    p.next_retry_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                    p.last_error = 'VECTOR_PROJECTION_FAILED', p.updated_at = CURRENT_TIMESTAMP(3)
                WHERE p.document_id = ? AND p.source_version = ? AND p.model_id = ?
                  AND p.status <> 'REMOVED'
                """, retrySeconds(generation.attempts()), generation.document().id(),
                generation.document().sourceVersion(), BgeChineseEmbedding.MODEL_ID);
    }

    static int retrySeconds(int priorAttempts) {
        return (int) Math.min(3600L, 30L << Math.max(0, Math.min(priorAttempts, 7)));
    }

    private JdbcTemplate connectionJdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private String lockName(JdbcTemplate work) {
        String database = work.queryForObject("SELECT DATABASE()", String.class);
        return "lh:vector:" + Integer.toUnsignedString(Objects.hash(database, BgeChineseEmbedding.MODEL_ID));
    }

    private record Generation(KnowledgeDocumentRecord document, boolean deleted, int attempts, boolean indexed) { }
}
