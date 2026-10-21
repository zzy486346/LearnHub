package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.assistant.knowledge.vector.ReciprocalRankFusion;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** 仅本机只读标定，已见72题为开发数据，不改首轮成绩、不执行回填或付费模型。 */
@EnabledIfSystemProperty(named = "learnhub.retrieval.threshold.calibrate", matches = "true")
@ContextConfiguration(initializers = LocalHybridBackfillIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml", "spring.profiles.active=vector",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.vector.enabled=true", "learnhub.vector.hybrid-enabled=true",
        "learnhub.vector.sync-enabled=false", "learnhub.ai.mode=DISABLED"
})
class LocalRetrievalThresholdCalibrationIntegrationTest {
    @MockBean LikeLegacyKeyMigration legacy;
    @MockBean LikeSequenceService sequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeStore store;
    @Autowired KnowledgeSearchGateway gateway;
    @Autowired KnowledgeVectorSearch vector;
    @Autowired CourseService courses;
    @Autowired KnowledgeRetrievalService retrieval;
    @Autowired org.springframework.core.env.Environment environment;

    @Test void recordsUnfilteredChannelScoresForTheAlreadySeenDataset() throws Exception {
        var root = CatalogMaterialCorpusTest.ROOT;
        JsonNode dataset = CatalogMaterialCorpusTest.JSON.readTree(Files.readString(
                root.resolve("docs/assistant-retrieval-local-20261020.json")));
        List<Long> users = jdbc.queryForList("SELECT id FROM user WHERE deleted=0 AND status='ACTIVE' ORDER BY id", Long.class);
        Map<Long, String> sourceKeys = new LinkedHashMap<>();
        jdbc.query("SELECT id,source_key FROM knowledge_document WHERE deleted=0", rs -> {
            sourceKeys.put(rs.getLong(1), rs.getString(2));
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : dataset.path("cases")) {
            long course = Long.parseLong(item.path("courseId").asText());
            List<Long> expectedLessons = new ArrayList<>();
            for (JsonNode key : item.path("expectedSourceKeys")) expectedLessons.add(jdbc.queryForObject(
                    "SELECT lesson_id FROM knowledge_document WHERE deleted=0 AND source_key=? AND course_id=?",
                    Long.class, key.asText(), course));
            Long reader = null;
            for (long user : users) {
                var readable = courses.readableLessonIds(user, course);
                if (!readable.isEmpty() && readable.containsAll(expectedLessons)) { reader = user; break; }
            }
            assertThat(reader).as("标定不能创建授权").isNotNull();
            var readable = courses.readableLessonIds(reader, course);
            String query = item.path("query").asText();
            var bm25 = verified(gateway.search(course, readable, query, 100), reader, course);
            var semantic = verified(vector.search(course, readable, query, 100), reader, course);
            var fused = ReciprocalRankFusion.fuse(bm25, semantic, 100);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.path("id").asText()); row.put("category", item.path("category").asText());
            row.put("courseId", course); row.put("query", query);
            row.put("expectedSourceKeys", item.path("expectedSourceKeys"));
            row.put("bm25", scores(bm25, sourceKeys)); row.put("semantic", scores(semantic, sourceKeys));
            row.put("rrf", scores(fused, sourceKeys)); rows.add(row);
            var filtered = retrieval.retrieve(reader, course, query, 5);
            assertThat(filtered.retrievalMode()).isEqualTo("HYBRID");
            var actual = filtered.sources().stream().map(source -> Map.<String, Object>of(
                    "chunkId", Long.parseLong(source.chunkId()), "sourceKey", sourceKeys.get(Long.parseLong(source.documentId())),
                    "score", source.score())).toList();
            row.put("filtered", actual);
        }
        assertThat(rows).hasSize(72);
        Files.createDirectories(root.resolve(".local"));
        Files.writeString(root.resolve(".local/retrieval-threshold-validation-20261021.json"),
                CatalogMaterialCorpusTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "datasetId", dataset.path("datasetId").asText(), "usage", "already-seen calibration data",
                        "rankConstant", 60, "supplierCalls", 0,
                        "minSemanticScore", environment.getRequiredProperty("learnhub.vector.min-semantic-score", Double.class),
                        "results", rows)));
    }

    private List<KnowledgeSearchGateway.Hit> verified(List<KnowledgeSearchGateway.Hit> hits, long reader, long course) {
        Set<Long> readable = Set.copyOf(courses.readableLessonIds(reader, course));
        var current = store.currentChunks(hits.stream().map(KnowledgeSearchGateway.Hit::chunkId).distinct().toList())
                .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, chunk -> chunk));
        return hits.stream().filter(hit -> {
            var chunk = current.get(hit.chunkId());
            return chunk != null && chunk.documentId() == hit.documentId() && chunk.sourceVersion() == hit.sourceVersion()
                    && chunk.courseId() == course && readable.contains(chunk.lessonId());
        }).toList();
    }

    private List<Map<String, Object>> scores(List<KnowledgeSearchGateway.Hit> hits, Map<Long, String> keys) {
        return hits.stream().map(hit -> Map.<String, Object>of("chunkId", hit.chunkId(),
                "sourceKey", keys.get(hit.documentId()), "score", hit.score())).toList();
    }
}
