package com.learnhub.assistant.knowledge.vector;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import com.learnhub.assistant.knowledge.KnowledgeChunkRecord;
import com.learnhub.assistant.knowledge.KnowledgeDocumentRecord;
import com.learnhub.assistant.knowledge.KnowledgeSearchGateway;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** 仅存可重建投影；调用方必须统一回 MySQL 复核，不得以 PG 正文回答。 */
public final class CourseVectorIndex {
    public static final String TABLE = "course_embeddings_bge_zh_v1";
    private final BgeChineseEmbedding embedding;
    private final PgVectorEmbeddingStore store;

    public CourseVectorIndex(DataSource privateDataSource, BgeChineseEmbedding embedding) {
        this.embedding = embedding;
        this.store = PgVectorEmbeddingStore.datasourceBuilder().datasource(privateDataSource)
                .table(TABLE).dimension(embedding.dimension()).createTable(false).dropTableFirst(false)
                .skipCreateVectorExtension(true).useIndex(false).build();
    }

    public void upsert(KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk) {
        if (document.id() != chunk.documentId() || document.sourceVersion() != chunk.sourceVersion())
            throw new IllegalArgumentException("Document version mismatch");
        var parts = embedding.splitDocument(chunk.content());
        // 单个知识片段最多 600 UTF-16 单元，投影批次仍设硬上限。
        for (int from = 0; from < parts.size(); from += 32) {
            int to = Math.min(from + 32, parts.size());
            var texts = parts.subList(from, to);
            var vectors = embedding.embedDocuments(texts);
            List<String> ids = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for (int part = from; part < to; part++) {
                String key = BgeChineseEmbedding.MODEL_ID + ":" + document.id() + ":"
                        + document.sourceVersion() + ":" + chunk.id() + ":" + part;
                ids.add(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
                var metadata = Metadata.from(Map.of("modelId", BgeChineseEmbedding.MODEL_ID,
                        "documentId", document.id(), "sourceVersion", document.sourceVersion(),
                        "chunkId", chunk.id(), "courseId", document.courseId(), "lessonId", document.lessonId(),
                        "part", part));
                segments.add(TextSegment.from(parts.get(part), metadata));
            }
            store.addAll(ids, vectors, segments);
        }
    }

    /** 先在 PG 过滤服务端授权课程/课时，返回 ID 而非正文。 */
    public List<KnowledgeSearchGateway.Hit> search(long courseId, List<Long> readableLessonIds,
                                                 Map<Long, Long> currentDocumentVersions,
                                                 String question, int limit) {
        if (courseId <= 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid vector search");
        if (readableLessonIds.isEmpty() || currentDocumentVersions.isEmpty()) return List.of();
        if (currentDocumentVersions.size() > 1000) throw new IllegalArgumentException("Vector scope too large");
        dev.langchain4j.store.embedding.filter.Filter versions = null;
        for (var entry : currentDocumentVersions.entrySet()) {
            if (entry.getKey() <= 0 || entry.getValue() <= 0) throw new IllegalArgumentException("Invalid document scope");
            var version = metadataKey("documentId").isEqualTo(entry.getKey().longValue())
                    .and(metadataKey("sourceVersion").isEqualTo(entry.getValue().longValue()));
            versions = versions == null ? version : versions.or(version);
        }
        var filter = metadataKey("modelId").isEqualTo(BgeChineseEmbedding.MODEL_ID)
                .and(metadataKey("courseId").isEqualTo(courseId))
                .and(metadataKey("lessonId").isIn(readableLessonIds)).and(versions);
        var response = store.search(EmbeddingSearchRequest.builder().queryEmbedding(embedding.embedQuery(question))
                .filter(filter).maxResults(limit * BgeChineseEmbedding.MAX_PARTS_PER_CHUNK).minScore(0.0).build());
        List<KnowledgeSearchGateway.Hit> hits = new ArrayList<>();
        var seen = new HashSet<Long>();
        for (var match : response.matches()) {
            var metadata = match.embedded().metadata();
            if (!seen.add(metadata.getLong("chunkId"))) continue;
            hits.add(new KnowledgeSearchGateway.Hit(metadata.getLong("chunkId"), metadata.getLong("documentId"),
                    metadata.getLong("sourceVersion"), match.score()));
            if (hits.size() == limit) break;
        }
        return List.copyOf(hits);
    }

    /** 旧任务只能删更老的代次，不能迟到删除新代次。 */
    public void removeOlderVersions(long documentId, long version) {
        if (documentId <= 0 || version <= 0) throw new IllegalArgumentException("Invalid projection identity");
        store.removeAll(metadataKey("modelId").isEqualTo(BgeChineseEmbedding.MODEL_ID)
                .and(metadataKey("documentId").isEqualTo(documentId))
                .and(metadataKey("sourceVersion").isLessThan(version)));
    }

    public void removeDocument(long documentId) {
        if (documentId <= 0) throw new IllegalArgumentException("Invalid projection identity");
        store.removeAll(metadataKey("modelId").isEqualTo(BgeChineseEmbedding.MODEL_ID)
                .and(metadataKey("documentId").isEqualTo(documentId)));
    }
}
