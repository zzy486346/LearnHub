package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.learnhub.assistant.knowledge.vector.BgeChineseEmbedding;
import com.learnhub.assistant.knowledge.vector.CourseVectorIndex;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.assistant.knowledge.vector.VectorProjectionSync;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** 仅显式维护命令执行：向本机课程库回填，不创建 HTTP 服务，不调用生成模型。 */
@EnabledIfSystemProperty(named = "learnhub.vector.local.backfill", matches = "true")
@ContextConfiguration(initializers = LocalHybridBackfillIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.vector.enabled=false", "learnhub.vector.embedding-provider=LOCAL", "learnhub.ai.mode=DISABLED"
})
class LocalHybridBackfillIntegrationTest {
    @MockBean LikeLegacyKeyMigration legacyLikeMigration;
    @MockBean LikeSequenceService likeSequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeStore knowledge;
    @Autowired KnowledgeSearchGateway gateway;
    @Autowired VectorProjectionSync sync;
    @Autowired CourseService courses;
    @Autowired Environment environment;

    static class LocalOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            new CatalogMaterialImportIntegrationTest.LocalOnly().initialize(context);
            if (!context.getEnvironment().getProperty("LEARNHUB_VECTOR_JDBC_URL", "")
                    .equals("jdbc:postgresql://127.0.0.1:15432/learnhub_vector")) {
                throw new IllegalStateException("课程向量回填仅允许本机15432端口的learnhub_vector数据库");
            }
        }
    }

    @Test
    void backfillsAllCurrentMaterialsAndRecordsFirstFrozenEvaluation() throws Exception {
        Path root = CatalogMaterialCorpusTest.ROOT;
        Path casesPath = root.resolve("docs/assistant-retrieval-local-20261020.json");
        byte[] casesBytes = Files.readAllBytes(casesPath);
        JsonNode dataset = CatalogMaterialCorpusTest.JSON.readTree(casesBytes);
        assertThat(dataset.path("cases").size()).isEqualTo(72);
        assertThat(dataset.withArray("cases").findValuesAsText("id")).doesNotHaveDuplicates();
        List<Map<String, Object>> materialsBefore = materialSnapshot();
        int documents = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE deleted=0", Integer.class);
        int expectedChunks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk k JOIN knowledge_document d
                  ON d.id=k.document_id AND d.source_version=k.source_version WHERE d.deleted=0
                """, Integer.class);
        assertThat(documents).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document WHERE deleted=0 AND
                  (indexed_version IS NULL OR indexed_version<>source_version OR index_status<>'INDEXED')
                """, Integer.class)).as("须先完成ES投影，不能拿不完整向量作评测").isZero();
        try (var pool = vectorPool(); var embedding = new BgeChineseEmbedding()) {
            JdbcTemplate pg = new JdbcTemplate(pool);
            verifyRuntimeRole(pg);
            var index = new CourseVectorIndex(pool, embedding);
            Set<String> generations = Set.copyOf(jdbc.queryForList("""
                    SELECT CONCAT(d.id,':',d.source_version,':',k.id) FROM knowledge_document d
                    JOIN knowledge_chunk k ON k.document_id=d.id AND k.source_version=d.source_version
                    WHERE d.deleted=0
                    """, String.class));
            if (!vectorGenerations(pg).equals(generations)) sync.requeueAll();
            int indexed = 0;
            for (int attempt = 0; attempt < 120; attempt++) {
                sync.syncDue(index, 100);
                indexed = completedDocuments();
                if (indexed == documents) break;
                Thread.sleep(1000);
            }
            assertThat(indexed).as("所有当前文档代次须完成且清理补偿结束").isEqualTo(documents);
            assertThat(vectorGenerations(pg)).as("向量身份必须精确覆盖当前文档、版本和片段，且不含旧代次")
                    .isEqualTo(generations);
            assertThat(pg.queryForObject("SELECT COUNT(DISTINCT metadata->>'chunkId') FROM learnhub_rag.course_embeddings_bge_zh_v1 WHERE metadata->>'modelId'=?",
                    Integer.class, BgeChineseEmbedding.MODEL_ID)).isEqualTo(expectedChunks);
            assertThat(pg.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1 WHERE public.vector_dims(embedding)<>512", Integer.class)).isZero();
            int vectorRows = pg.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1", Integer.class);
            long materialEncodings = embedding.embeddingCount();
            assertThat(sync.syncDue(index, 100)).as("重复同步应无待处理代次").isZero();
            assertThat(embedding.embeddingCount()).isEqualTo(materialEncodings);
            assertThat(pg.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1", Integer.class)).isEqualTo(vectorRows);

            KnowledgeVectorSearch vector = new KnowledgeVectorSearch() {
                @Override public boolean hybridEnabled() { return true; }
                @Override public List<KnowledgeSearchGateway.Hit> search(long course, List<Long> readable,
                        String question, int limit) {
                    return index.search(course, readable, sync.currentVersions(course, readable), question, limit);
                }
            };
            var factory = new StaticListableBeanFactory(Map.of("vector", vector));
            var bm25 = new KnowledgeRetrievalService(knowledge, gateway, courses);
            // 2026-10-20首次无门槛基线：新门槛另由标定集成测试验收，不能改写旧成绩。
            var hybrid = new KnowledgeRetrievalService(knowledge, gateway, courses, factory.getBeanProvider(KnowledgeVectorSearch.class), 0);
            List<Long> users = jdbc.queryForList("SELECT id FROM user WHERE status='ACTIVE' AND deleted=0 ORDER BY id", Long.class);
            assertThat(users).isNotEmpty();
            Map<Long, String> sourceKeys = new LinkedHashMap<>();
            jdbc.query("SELECT id,source_key FROM knowledge_document WHERE deleted=0", rs -> {
                sourceKeys.put(rs.getLong(1), rs.getString(2));
            });
            embedding.embedQuery("本地检索预热");
            List<Map<String, Object>> results = new ArrayList<>();
            int number = 0;
            List<String> modes = List.of("BM25", "SEMANTIC", "HYBRID");
            for (JsonNode item : dataset.withArray("cases")) {
                long course = Long.parseLong(item.path("courseId").asText());
                Set<String> expected = strings(item.withArray("expectedSourceKeys"));
                long reader = existingReader(users, course, expected);
                for (String key : expected) assertThat(sourceKeys).containsValue(key);
                Set<String> forbidden = strings(item.withArray("forbiddenCourseIds"));
                for (int offset = 0; offset < modes.size(); offset++) {
                    String mode = modes.get((number + offset) % modes.size());
                    long start = System.nanoTime();
                    List<Observed> found = switch (mode) {
                        case "BM25" -> observed(bm25.retrieve(reader, course, item.path("query").asText(), 5));
                        case "HYBRID" -> {
                            var response = hybrid.retrieve(reader, course, item.path("query").asText(), 5);
                            assertThat(response.retrievalMode()).isEqualTo("HYBRID");
                            yield observed(response);
                        }
                        default -> semantic(reader, course, item.path("query").asText(), vector);
                    };
                    double millis = (System.nanoTime() - start) / 1_000_000.0;
                    List<Long> readable = courses.readableLessonIds(reader, course);
                    assertThat(found).allSatisfy(hit -> {
                        assertThat(hit.course()).isEqualTo(course);
                        assertThat(readable).contains(hit.lesson());
                        assertThat(forbidden).doesNotContain(Long.toString(hit.course()));
                    });
                    List<String> keys = found.stream().map(hit -> sourceKeys.get(hit.document())).toList();
                    int firstRank = 0;
                    for (int rank = 0; rank < keys.size(); rank++) {
                        if (expected.contains(keys.get(rank))) { firstRank = rank + 1; break; }
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.path("id").asText()); row.put("category", item.path("category").asText());
                    row.put("mode", mode); row.put("sourceKeys", keys); row.put("firstExpectedRank", firstRank);
                    row.put("sourceCount", found.size()); row.put("latencyMs", millis);
                    results.add(row);
                }
                number++;
            }
            int deniedChecks = verifyUnreadableLessons(users, bm25, hybrid, vector);
            assertThat(materialSnapshot()).as("回填不能修改MySQL材料或版本").isEqualTo(materialsBefore);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("datasetId", dataset.path("datasetId").asText());
            report.put("datasetCommit", "30f656e"); report.put("datasetSha256", sha256(casesBytes));
            report.put("activeProfiles", List.of(environment.getActiveProfiles()));
            report.put("materialSnapshotSha256", sha256(CatalogMaterialCorpusTest.JSON.copy()
                    .findAndRegisterModules().writeValueAsBytes(materialsBefore)));
            report.put("documents", documents); report.put("currentChunks", expectedChunks);
            report.put("vectorRows", vectorRows); report.put("dimension", 512);
            report.put("modelId", BgeChineseEmbedding.MODEL_ID); report.put("rankConstant", 60);
            report.put("candidateLimitPerChannel", 100); report.put("returnedChunkLimit", 5);
            report.put("timing", "Warm local model; per-case rotated mode order; permission and version reads included; assertions excluded");
            report.put("materialEncodingsThisRun", materialEncodings);
            report.put("queryEncodingsIncludingWarmup", embedding.embeddingCount() - materialEncodings);
            report.put("deniedScopeChecks", deniedChecks); report.put("authorizationViolations", 0);
            report.put("supplierCalls", 0); report.put("metrics", metrics(results)); report.put("results", results);
            Files.createDirectories(root.resolve(".local"));
            String json = CatalogMaterialCorpusTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(root.resolve(".local/vector-local-latest.json"), json);
            Path first = root.resolve("docs/assistant-vector-local-first-20261020.json");
            try { Files.writeString(first, json, java.nio.file.StandardOpenOption.CREATE_NEW); }
            catch (java.nio.file.FileAlreadyExistsException preserved) { /* 首轮回执永久保留。 */ }
        }
    }

    private HikariDataSource vectorPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(environment.getProperty("LEARNHUB_VECTOR_JDBC_URL")
                + "?currentSchema=learnhub_rag,public&connectTimeout=2&socketTimeout=5");
        config.setUsername(environment.getRequiredProperty("LEARNHUB_VECTOR_USERNAME"));
        config.setPassword(environment.getRequiredProperty("LEARNHUB_VECTOR_PASSWORD"));
        config.setMaximumPoolSize(3); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setPoolName("learnhub-vector-local-maintenance");
        return new HikariDataSource(config);
    }

    private void verifyRuntimeRole(JdbcTemplate pg) {
        assertThat(pg.queryForObject("SELECT current_database()", String.class)).isEqualTo("learnhub_vector");
        assertThat(pg.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(pg.queryForObject("SELECT has_schema_privilege(current_user,'learnhub_rag','CREATE') OR has_schema_privilege(current_user,'public','CREATE')", Boolean.class)).isFalse();
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE"))
            assertThat(pg.queryForObject("SELECT has_table_privilege(current_user,'learnhub_rag.course_embeddings_bge_zh_v1',?)", Boolean.class, privilege)).isTrue();
    }

    private int completedDocuments() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_vector_projection p JOIN knowledge_document d
                  ON d.id=p.document_id AND d.source_version=p.source_version
                WHERE d.deleted=0 AND p.model_id=? AND p.status='INDEXED' AND p.cleanup_pending=0
                """, Integer.class, BgeChineseEmbedding.MODEL_ID);
    }

    private long existingReader(List<Long> users, long course, Set<String> expected) {
        List<Long> expectedLessons = new ArrayList<>();
        for (String key : expected) expectedLessons.add(jdbc.queryForObject(
                "SELECT lesson_id FROM knowledge_document WHERE deleted=0 AND source_key=? AND course_id=?",
                Long.class, key, course));
        for (long user : users) {
            List<Long> readable = courses.readableLessonIds(user, course);
            if (!readable.isEmpty() && readable.containsAll(expectedLessons)) return user;
        }
        throw new IllegalStateException("冻结问题没有现成可读用户，禁止为评测创建购买授权");
    }

    private List<Observed> semantic(long user, long course, String query, KnowledgeVectorSearch vector) {
        List<Long> readable = courses.readableLessonIds(user, course);
        if (readable.isEmpty()) return List.of();
        var hits = vector.search(course, readable, query, 100);
        var current = knowledge.currentChunks(hits.stream().map(KnowledgeSearchGateway.Hit::chunkId).distinct().toList())
                .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, chunk -> chunk));
        List<Long> refreshed = courses.readableLessonIds(user, course);
        List<Observed> result = new ArrayList<>();
        for (var hit : hits) {
            var chunk = current.get(hit.chunkId());
            if (chunk != null && chunk.courseId() == course && refreshed.contains(chunk.lessonId())
                    && chunk.documentId() == hit.documentId() && chunk.sourceVersion() == hit.sourceVersion()) {
                result.add(new Observed(chunk.documentId(), course, chunk.lessonId()));
                if (result.size() == 5) break;
            }
        }
        return result;
    }

    private List<Observed> observed(KnowledgeRetrievalService.Result result) {
        return result.sources().stream().map(source -> new Observed(Long.parseLong(source.documentId()),
                Long.parseLong(source.courseId()), Long.parseLong(source.lessonId()))).toList();
    }

    private int verifyUnreadableLessons(List<Long> users, KnowledgeRetrievalService bm25,
            KnowledgeRetrievalService hybrid, KnowledgeVectorSearch vector) throws Exception {
        int checks = 0;
        for (JsonNode item : CatalogMaterialCorpusTest.materials()) {
            long course = Long.parseLong(item.path("courseId").asText());
            long lesson = Long.parseLong(item.path("lessonId").asText());
            for (long user : users) {
                if (courses.readableLessonIds(user, course).contains(lesson)) continue;
                String query = item.path("smokeQuestion").asText();
                assertThat(observed(bm25.retrieve(user, course, query, 5))).noneMatch(hit -> hit.lesson() == lesson);
                assertThat(observed(hybrid.retrieve(user, course, query, 5))).noneMatch(hit -> hit.lesson() == lesson);
                assertThat(semantic(user, course, query, vector)).noneMatch(hit -> hit.lesson() == lesson);
                checks += 3;
                break;
            }
        }
        assertThat(checks).as("至少存在一个真实未授权课时检查").isPositive();
        return checks;
    }

    private Map<String, Object> metrics(List<Map<String, Object>> results) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String mode : List.of("BM25", "SEMANTIC", "HYBRID")) {
            var rows = results.stream().filter(row -> mode.equals(row.get("mode"))).toList();
            var answerable = rows.stream().filter(row -> "ANSWERABLE".equals(row.get("category"))).toList();
            var negative = rows.stream().filter(row -> "NO_EVIDENCE".equals(row.get("category"))).toList();
            List<Double> times = rows.stream().map(row -> (Double) row.get("latencyMs")).sorted().toList();
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("queries", rows.size()); metric.put("answerableQueries", answerable.size());
            metric.put("sourceRecallAt5Hits", answerable.stream().filter(row -> (Integer) row.get("firstExpectedRank") > 0).count());
            metric.put("sourceMrrAt5", answerable.stream().mapToDouble(row -> (Integer) row.get("firstExpectedRank") == 0 ? 0 : 1.0 / (Integer) row.get("firstExpectedRank")).average().orElse(0));
            metric.put("noEvidenceQueries", negative.size());
            metric.put("noEvidenceQueriesWithSources", negative.stream().filter(row -> (Integer) row.get("sourceCount") > 0).count());
            metric.put("weakSourcesForNoEvidence", negative.stream().mapToInt(row -> (Integer) row.get("sourceCount")).sum());
            metric.put("meanLatencyMs", times.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            metric.put("p95LatencyMs", times.get((int) Math.ceil(times.size() * 0.95) - 1));
            result.put(mode, metric);
        }
        return result;
    }

    private List<Map<String, Object>> materialSnapshot() {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (String table : List.of("knowledge_document", "knowledge_chunk")) {
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")) {
                Map<String, Object> entry = new LinkedHashMap<>(row);
                entry.put("snapshot_table", table);
                snapshot.add(entry);
            }
        }
        return snapshot;
    }

    private Set<String> vectorGenerations(JdbcTemplate pg) {
        return Set.copyOf(pg.queryForList("""
                SELECT DISTINCT (metadata->>'documentId') || ':' || (metadata->>'sourceVersion')
                    || ':' || (metadata->>'chunkId') FROM learnhub_rag.course_embeddings_bge_zh_v1
                WHERE metadata->>'modelId'=?
                """, String.class, BgeChineseEmbedding.MODEL_ID));
    }

    private Set<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).collect(Collectors.toSet());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Observed(long document, long course, long lesson) { }
}
