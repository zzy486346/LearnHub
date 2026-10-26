package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** 显式只读业务烟测；不回填、不调用Embedding/聊天生成，不启动日常HTTP后端。 */
@EnabledIfSystemProperty(named = "learnhub.rerank.live.enabled", matches = "true")
@ContextConfiguration(initializers = LocalHybridBackfillIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.vector.enabled=false", "learnhub.rerank.enabled=true", "learnhub.ai.mode=DISABLED",
        "logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR"
})
class SiliconFlowRerankLiveIntegrationTest {
    @MockBean LikeLegacyKeyMigration legacyLikeMigration;
    @MockBean LikeSequenceService likeSequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseService courses;
    @Autowired KnowledgeStore store;
    @Autowired KnowledgeRetrievalService retrieval;

    @Test void reranksThreeSeenQueriesThroughReadOnlyAuthorizedApplicationPath() throws Exception {
        Path root = CatalogMaterialCorpusTest.ROOT;
        var json = new ObjectMapper();
        var dataset = json.readTree(root.resolve("docs/assistant-retrieval-local-20261020.json").toFile());
        var before = sourceFingerprint();
        var users = jdbc.queryForList("SELECT id FROM user WHERE status='ACTIVE' AND deleted=0 ORDER BY id", Long.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        int applied = 0;
        for (var item : dataset.path("cases")) {
            if (!"ANSWERABLE".equals(item.path("category").asText())) continue;
            long course = Long.parseLong(item.path("courseId").asText());
            Long reader = null;
            for (long user : users) if (!courses.readableLessonIds(user, course).isEmpty()) { reader = user; break; }
            if (reader == null) continue;
            long start = System.nanoTime();
            try (var scope = new com.learnhub.assistant.runtime.AssistantIoScope(Duration.ofSeconds(15))) {
                scope.attach();
                var result = retrieval.retrieve(reader, course, item.path("query").asText(), 5);
                assertThat(result.retrievalMode()).isEqualTo("BM25");
                assertThat(result.rerankMode()).isIn("APPLIED", "FALLBACK", "SKIPPED");
                if ("APPLIED".equals(result.rerankMode())) applied++;
                var readable = courses.readableLessonIds(reader, course);
                assertThat(result.sources()).isNotEmpty();
                for (var source : result.sources()) {
                    var current = store.currentChunk(Long.parseLong(source.chunkId())).orElseThrow();
                    assertThat(current.courseId()).isEqualTo(course);
                    assertThat(readable).contains(current.lessonId());
                    assertThat(current.documentId()).isEqualTo(Long.parseLong(source.documentId()));
                    assertThat(current.sourceVersion()).isEqualTo(source.sourceVersion());
                    assertThat(source.text().equals(current.content())).isTrue();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("caseId", item.path("id").asText()); row.put("retrievalMode", result.retrievalMode());
                row.put("rerankMode", result.rerankMode()); row.put("sources", result.sources().size());
                row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000.0);
                rows.add(row);
            }
            if (rows.size() == 3) break;
        }
        assertThat(rows).hasSize(3);
        assertThat(applied).as("不能全部降级却宣称真实重排成功").isPositive();
        assertThat(sourceFingerprint()).isEqualTo(before);
        Map<String, Object> report = Map.of("model", "BAAI/bge-reranker-v2-m3", "cases", rows,
                "applied", applied, "materialsUnchanged", true, "qualityEvaluated", false,
                "datasetStatus", "SEEN_DEVELOPMENT_SMOKE");
        Files.createDirectories(root.resolve(".local"));
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve(".local/rerank-live-application.json").toFile(), report);
        System.out.println("Rerank live smoke: cases=" + rows.size() + " applied=" + applied);
    }

    private List<Map<String, Object>> sourceFingerprint() {
        return jdbc.queryForList("""
                SELECT d.id,d.source_version,d.content_hash,d.deleted,k.id AS chunk_id,SHA2(k.content,256) AS chunk_hash
                FROM knowledge_document d JOIN knowledge_chunk k ON k.document_id=d.id
                ORDER BY d.id,k.id
                """);
    }
}
