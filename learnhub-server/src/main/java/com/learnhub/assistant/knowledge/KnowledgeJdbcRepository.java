package com.learnhub.assistant.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class KnowledgeJdbcRepository {
    private final JdbcTemplate jdbc;

    KnowledgeJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean validLessonBinding(long courseId, long lessonId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM course c
                JOIN course_chapter cc ON cc.course_id = c.id
                JOIN course_lesson cl ON cl.chapter_id = cc.id
                WHERE c.id = ? AND cl.id = ? AND c.deleted = 0 AND cl.deleted = 0
                """, Long.class, courseId, lessonId);
        return count != null && count == 1L;
    }

    void ensureDocument(String sourceKey, String title, long courseId, long lessonId, String contentHash) {
        jdbc.update("""
                INSERT INTO knowledge_document
                    (source_key, title, course_id, lesson_id, visibility, content_hash, source_version,
                     index_status, attempts, next_retry_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, 'COURSE_LESSON', ?, 1, 'PENDING', 0, CURRENT_TIMESTAMP(3),
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                ON DUPLICATE KEY UPDATE source_key = VALUES(source_key)
                """, sourceKey, title, courseId, lessonId, contentHash);
    }

    LockedDocument lockBySourceKey(String sourceKey) {
        return jdbc.queryForObject("""
                SELECT id, title, course_id, lesson_id, content_hash, source_version, deleted
                FROM knowledge_document
                WHERE source_key = ?
                FOR UPDATE
                """, (rs, rowNum) -> new LockedDocument(rs.getLong("id"), rs.getString("title"),
                rs.getLong("course_id"), rs.getLong("lesson_id"), rs.getString("content_hash"),
                rs.getLong("source_version"), rs.getBoolean("deleted")), sourceKey);
    }

    int chunkCount(long documentId, long version) {
        // 等待来源行锁后使用当前读，避免 RR 旧快照漏看另一导入事务已提交的片段。
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ? AND source_version = ? FOR UPDATE
                """, Integer.class, documentId, version);
        return count == null ? 0 : count;
    }

    void updateVersion(long documentId, String title, long courseId, long lessonId,
                       String contentHash, long sourceVersion) {
        jdbc.update("""
                UPDATE knowledge_document
                SET title = ?, course_id = ?, lesson_id = ?, content_hash = ?, source_version = ?,
                    index_status = 'PENDING', attempts = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND deleted = 0
                """, title, courseId, lessonId, contentHash, sourceVersion, documentId);
    }

    void insertChunks(long documentId, long version, List<KnowledgeChunker.DraftChunk> chunks) {
        jdbc.batchUpdate("""
                INSERT INTO knowledge_chunk
                    (document_id, source_version, chunk_order, heading, content, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """, chunks, chunks.size(), (statement, chunk) -> {
            statement.setLong(1, documentId);
            statement.setLong(2, version);
            statement.setInt(3, chunk.order());
            statement.setString(4, chunk.heading());
            statement.setString(5, chunk.content());
        });
    }

    List<KnowledgeDocumentRecord> pending(int limit) {
        return jdbc.query("""
                SELECT id, source_version, course_id, lesson_id, title
                FROM knowledge_document
                WHERE deleted = 0 AND index_status IN ('PENDING', 'FAILED')
                  AND next_retry_at <= CURRENT_TIMESTAMP(3)
                ORDER BY next_retry_at, id
                LIMIT ?
                """, (rs, rowNum) -> document(rs), limit);
    }

    List<KnowledgeChunkRecord> chunks(long documentId, long version) {
        return jdbc.query("""
                SELECT id, document_id, source_version, chunk_order, heading, content
                FROM knowledge_chunk
                WHERE document_id = ? AND source_version = ?
                ORDER BY chunk_order
                """, (rs, rowNum) -> chunk(rs), documentId, version);
    }

    boolean markIndexed(long documentId, long version) {
        return jdbc.update("""
                UPDATE knowledge_document
                SET indexed_version = ?, index_status = 'INDEXED', attempts = 0, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND source_version = ? AND deleted = 0
                """, version, documentId, version) == 1;
    }

    boolean markFailed(long documentId, long version) {
        return jdbc.update("""
                UPDATE knowledge_document
                SET next_retry_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL
                        CASE
                            WHEN attempts >= 8 THEN 300
                            ELSE POW(2, attempts + 1)
                        END SECOND),
                    index_status = 'FAILED', attempts = attempts + 1,
                    last_error = '知识索引同步失败', updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND source_version = ? AND deleted = 0
                """, documentId, version) == 1;
    }

    void requeueAll() {
        jdbc.update("""
                UPDATE knowledge_document
                SET indexed_version = 0, index_status = 'PENDING', attempts = 0,
                    next_retry_at = CURRENT_TIMESTAMP(3), last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE deleted = 0
                """);
    }

    Optional<CurrentKnowledgeChunk> currentChunk(long chunkId) {
        return currentChunks(List.of(chunkId)).stream().findFirst();
    }

    List<CurrentKnowledgeChunk> currentChunks(List<Long> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        List<CurrentKnowledgeChunk> rows = jdbc.query("""
                SELECT kc.id, kc.document_id, kc.source_version, kd.course_id, kd.lesson_id, kd.title,
                       kc.chunk_order, kc.heading, kc.content
                FROM knowledge_chunk kc
                JOIN knowledge_document kd ON kd.id = kc.document_id
                WHERE kc.id IN (%s) AND kd.deleted = 0
                  AND kd.source_version = kc.source_version
                  AND kd.indexed_version = kd.source_version
                """.formatted(placeholders), (rs, rowNum) -> currentChunk(rs), chunkIds.toArray());
        return rows;
    }

    private KnowledgeDocumentRecord document(ResultSet rs) throws SQLException {
        return new KnowledgeDocumentRecord(rs.getLong("id"), rs.getLong("source_version"),
                rs.getLong("course_id"), rs.getLong("lesson_id"), rs.getString("title"));
    }

    private KnowledgeChunkRecord chunk(ResultSet rs) throws SQLException {
        return new KnowledgeChunkRecord(rs.getLong("id"), rs.getLong("document_id"),
                rs.getLong("source_version"), rs.getInt("chunk_order"), rs.getString("heading"),
                rs.getString("content"));
    }

    private CurrentKnowledgeChunk currentChunk(ResultSet rs) throws SQLException {
        return new CurrentKnowledgeChunk(rs.getLong("id"), rs.getLong("document_id"),
                rs.getLong("source_version"), rs.getLong("course_id"), rs.getLong("lesson_id"),
                rs.getString("title"), rs.getInt("chunk_order"), rs.getString("heading"),
                rs.getString("content"));
    }

    record LockedDocument(long id, String title, long courseId, long lessonId,
                          String contentHash, long sourceVersion, boolean deleted) {
    }
}
