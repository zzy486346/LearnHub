package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.vector.CourseVectorIndex;
import com.learnhub.assistant.knowledge.vector.SiliconFlowBgeEmbedding;
import com.learnhub.assistant.knowledge.vector.VectorProjectionSync;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** 显式维护入口：只重建远程课程投影，不启动HTTP后端，不调用聊天生成模型。 */
@EnabledIfSystemProperty(named = "learnhub.vector.siliconflow.backfill", matches = "true")
@ContextConfiguration(initializers = LocalHybridBackfillIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.vector.enabled=false", "learnhub.vector.embedding-provider=SILICONFLOW", "learnhub.ai.mode=DISABLED"
})
class SiliconFlowVectorBackfillIntegrationTest {
    @MockBean LikeLegacyKeyMigration legacyLikeMigration;
    @MockBean LikeSequenceService likeSequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeStore knowledge;
    @Autowired KnowledgeSearchGateway gateway;
    @Autowired VectorProjectionSync sync;
    @Autowired CourseService courses;
    @Autowired Environment environment;

    @Test void backfillsRemoteProjectionWithoutChangingMaterialsOrLocalVectors() throws Exception {
        assertThat(environment.getRequiredProperty("LEARNHUB_VECTOR_JDBC_URL"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:15432/learnhub_vector");
        List<Map<String, Object>> before = materialSnapshot();
        int documents = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE deleted=0", Integer.class);
        assertThat(documents).isBetween(1, 1000);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document WHERE deleted=0 AND
                  (indexed_version IS NULL OR indexed_version<>source_version OR index_status<>'INDEXED')
                """, Integer.class)).isZero();
        Set<String> expected = Set.copyOf(jdbc.queryForList("""
                SELECT CONCAT(d.id,':',d.source_version,':',k.id) FROM knowledge_document d
                JOIN knowledge_chunk k ON k.document_id=d.id AND k.source_version=d.source_version WHERE d.deleted=0
                """, String.class));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(environment.getRequiredProperty("LEARNHUB_VECTOR_JDBC_URL")
                + "?currentSchema=learnhub_rag,public&connectTimeout=2&socketTimeout=5");
        config.setUsername(environment.getRequiredProperty("LEARNHUB_VECTOR_USERNAME"));
        config.setPassword(environment.getRequiredProperty("LEARNHUB_VECTOR_PASSWORD"));
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setPoolName("siliconflow-backfill-maintenance");
        try (var pool = new HikariDataSource(config);
             var embedding = new SiliconFlowBgeEmbedding(URI.create(environment.getProperty(
                     "LEARNHUB_VECTOR_SILICONFLOW_ENDPOINT", "https://api.siliconflow.cn/v1/embeddings")),
                     environment.getRequiredProperty("LEARNHUB_VECTOR_SILICONFLOW_API_KEY"), Duration.ofSeconds(10))) {
            JdbcTemplate pg = new JdbcTemplate(pool);
            assertThat(pg.queryForObject("SELECT current_database()", String.class)).isEqualTo("learnhub_vector");
            assertThat(pg.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user",
                    Boolean.class)).isFalse();
            List<Map<String, Object>> localBefore = localSnapshot(pg);
            var index = new CourseVectorIndex(pool, embedding);
            // 只有MySQL宣称全部完成但PG身份缺失时才修复已完成状态；正常部分回填沿既有失败退避补偿。
            // 不能因为部分失败就把全部成功文档重新编码。
            if (!identities(pg).equals(expected) && completedDocuments() == documents) sync.requeueAll();
            int completed = 0;
            // 有界维护批次；失败留在补偿表，不通过删除旧模型或修改正文强行完成。
            for (int batch = 0; batch < 100; batch++) {
                sync.syncDue(index, 100);
                completed = completedDocuments();
                if (completed == documents) break;
                if (jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_vector_projection WHERE model_id=? AND status='FAILED'",
                        Integer.class, SiliconFlowBgeEmbedding.MODEL_ID) > 0)
                    throw new IllegalStateException("Remote backfill failed; inspect redacted projection status before retry");
            }
            assertThat(completed).isEqualTo(documents);
            assertThat(identities(pg)).isEqualTo(expected);
            assertThat(pg.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_sf_bge_large_zh_v1 "
                    + "WHERE public.vector_dims(embedding)<>1024", Integer.class)).isZero();
            long encodings = embedding.embeddingCount();
            assertThat(sync.syncDue(index, 100)).isZero();
            assertThat(embedding.embeddingCount()).isEqualTo(encodings);
            Map<String, Integer> checks = authorizedSearchSmoke(index);
            assertThat(materialSnapshot()).isEqualTo(before);
            assertThat(localSnapshot(pg)).isEqualTo(localBefore);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", SiliconFlowBgeEmbedding.MODEL);
            report.put("modelId", SiliconFlowBgeEmbedding.MODEL_ID);
            report.put("dimension", 1024); report.put("documents", documents);
            report.put("chunks", expected.size()); report.put("embeddingTexts", embedding.embeddingCount());
            report.put("projectionRows", pg.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_sf_bge_large_zh_v1", Integer.class));
            report.put("repeatSyncEmbeddingTexts", 0); report.put("authorizedSearch", checks);
            report.put("materialsUnchanged", true); report.put("localProjectionUnchanged", true);
            report.put("generationQualityEvaluated", false);
            Path root = CatalogMaterialCorpusTest.ROOT;
            Path output = root.resolve(".local/siliconflow-vector-backfill.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("SiliconFlow backfill: documents=" + documents + " chunks=" + expected.size()
                    + " embeddingTexts=" + embedding.embeddingCount() + " authorizedChecks=" + checks);
        }
    }

    private int completedDocuments() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_vector_projection p JOIN knowledge_document d
                ON d.id=p.document_id AND d.source_version=p.source_version
                WHERE d.deleted=0 AND p.model_id=? AND p.status='INDEXED' AND p.cleanup_pending=0
                """, Integer.class, SiliconFlowBgeEmbedding.MODEL_ID);
    }

    private Map<String, Integer> authorizedSearchSmoke(CourseVectorIndex index) {
        List<Long> users = jdbc.queryForList("SELECT id FROM user WHERE status='ACTIVE' AND deleted=0 ORDER BY id", Long.class);
        List<Long> courseIds = jdbc.queryForList("SELECT DISTINCT course_id FROM knowledge_document WHERE deleted=0 ORDER BY course_id", Long.class);
        KnowledgeVectorSearch vector = new KnowledgeVectorSearch() {
            @Override public boolean hybridEnabled() { return true; }
            @Override public List<KnowledgeSearchGateway.Hit> search(long course, List<Long> readable, String query, int limit) {
                return index.search(course, readable, sync.currentVersions(course, readable), query, limit);
            }
        };
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("vector", vector));
        var retrieval = new KnowledgeRetrievalService(knowledge, gateway, courses,
                factory.getBeanProvider(KnowledgeVectorSearch.class), 0);
        int checked = 0;
        int remote = 0;
        int fallback = 0;
        for (long course : courseIds) {
            for (long owner : users) {
                var readable = courses.readableLessonIds(owner, course);
                var versions = sync.currentVersions(course, readable);
                if (versions.isEmpty()) continue;
                // 用真实应用出口验收供应商失败后的受控降级；失败必须计入回执，不能伪装远程成功。
                var result = retrieval.retrieve(owner, course, "课程学习内容", 5);
                if ("HYBRID".equals(result.retrievalMode())) {
                    remote++;
                    assertThat(result.sources()).isNotEmpty();
                } else {
                    assertThat(result.retrievalMode()).isEqualTo("BM25_FALLBACK");
                    fallback++;
                }
                var refreshed = courses.readableLessonIds(owner, course);
                assertThat(result.sources()).allMatch(source -> Long.parseLong(source.courseId()) == course
                        && refreshed.contains(Long.parseLong(source.lessonId()))
                        && source.text() != null && !source.text().isBlank());
                checked++;
                break;
            }
        }
        assertThat(checked).isPositive();
        assertThat(remote).as("至少一次真实远程召回，不能全部降级却宣称远程接入成功").isPositive();
        return Map.of("total", checked, "remoteSuccesses", remote, "bm25Fallbacks", fallback);
    }

    private Set<String> identities(JdbcTemplate pg) {
        return Set.copyOf(pg.queryForList("""
                SELECT DISTINCT (metadata->>'documentId') || ':' || (metadata->>'sourceVersion') || ':' || (metadata->>'chunkId')
                FROM learnhub_rag.course_embeddings_sf_bge_large_zh_v1 WHERE metadata->>'modelId'=?
                """, String.class, SiliconFlowBgeEmbedding.MODEL_ID));
    }

    private List<Map<String, Object>> materialSnapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String table : List.of("knowledge_document", "knowledge_chunk"))
            result.addAll(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }

    private List<Map<String, Object>> localSnapshot(JdbcTemplate pg) {
        return pg.queryForList("SELECT embedding_id, embedding::text, text, metadata::text, created_at, updated_at "
                + "FROM learnhub_rag.course_embeddings_bge_zh_v1 ORDER BY embedding_id");
    }
}
