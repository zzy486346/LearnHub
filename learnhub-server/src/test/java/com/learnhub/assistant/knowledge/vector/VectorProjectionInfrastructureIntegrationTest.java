package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.ImportDocumentRequest;
import com.learnhub.assistant.knowledge.KnowledgeIndexSyncService;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeSearchGateway;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.course.service.CourseService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 仅连接 verify-vector.ps1 本次创建的 MySQL / PG 容器，不访问业务数据库。 */
@EnabledIfSystemProperty(named = "learnhub.vector.it.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VectorProjectionInfrastructureIntegrationTest {
    private JdbcTemplate mysql;
    private JdbcTemplate postgres;
    private VectorProjectionSync sync;
    private CourseVectorIndex index;
    private BgeChineseEmbedding embedding;
    private AnnotationConfigApplicationContext context;

    @BeforeAll
    void initializeOwnedDatabases() throws Exception {
        String mysqlPort = requiredPort("LEARNHUB_VECTOR_IT_MYSQL_PORT");
        var mysqlSource = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + mysqlPort
                + "/learnhub_vector_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root", requiredPassword("LEARNHUB_VECTOR_IT_MYSQL_PASSWORD"));
        mysql = new JdbcTemplate(mysqlSource);
        assertThat(mysql.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("learnhub_vector_it");
        String schema = Files.readString(projectFile("sql/schema.sql"))
                .replaceAll("(?im)^CREATE DATABASE[^\\r\\n]*[\\r\\n]*", "")
                .replaceAll("(?im)^USE learnhub;[\\r\\n]*", "");
        var schemaPopulator = new ResourceDatabasePopulator(
                new ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        schemaPopulator.setSqlScriptEncoding("UTF-8");
        schemaPopulator.execute(mysqlSource);
        byte[] migration = Files.readAllBytes(projectFile("sql/migrations/V20261019__knowledge_vector_projection.sql"));
        // schema 与重复执行的真实迁移必须一致，且不会重新创建业务数据库。
        for (int repeat = 0; repeat < 2; repeat++) {
            var migrationPopulator = new ResourceDatabasePopulator(new ByteArrayResource(migration));
            migrationPopulator.setSqlScriptEncoding("UTF-8");
            migrationPopulator.execute(mysqlSource);
        }
        assertThat(mysql.queryForObject("""
                SELECT TABLE_COMMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_vector_projection'
                """, String.class)).contains("向量投影");
        assertThat(mysql.queryForList("""
                SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_vector_projection'
                """, String.class)).isNotEmpty().allMatch(comment -> comment.matches(".*[\\p{IsHan}].*"));

        String pgPort = requiredPort("LEARNHUB_VECTOR_IT_PORT");
        var pgSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + pgPort
                + "/learnhub_vector_it?currentSchema=learnhub_rag,public&connectTimeout=2&socketTimeout=5",
                "postgres", requiredPassword("LEARNHUB_VECTOR_IT_PASSWORD"));
        postgres = new JdbcTemplate(pgSource);
        assertThat(postgres.queryForObject("SELECT current_database()", String.class)).isEqualTo("learnhub_vector_it");
        try (var connection = pgSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(Files.readString(projectFile("sql/postgres/001_course_embeddings.sql")));
        }
        // 仓储类有意保持包可见；通过普通 Spring 注册创建真实实现，不复制仓储 SQL。
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> mysql);
        context.registerBean(Class.forName("com.learnhub.assistant.knowledge.KnowledgeJdbcRepository"));
        context.registerBean(KnowledgeStore.class);
        context.refresh();
        sync = new VectorProjectionSync(mysql, context.getBean(KnowledgeStore.class));
        embedding = new BgeChineseEmbedding();
        index = new CourseVectorIndex(pgSource, embedding);
    }

    @BeforeEach
    void cleanOwnedProjectionAndMaterialTables() {
        postgres.execute("TRUNCATE learnhub_rag.course_embeddings_bge_zh_v1");
        mysql.execute("TRUNCATE TABLE knowledge_vector_projection");
        mysql.execute("TRUNCATE TABLE knowledge_chunk");
        mysql.execute("TRUNCATE TABLE knowledge_document");
    }

    @AfterAll
    void releaseOwnedResources() {
        if (embedding != null) embedding.close();
        if (context != null) context.close();
    }

    @Test
    void repeatedSyncVersionSwitchAndExplicitRebuildAreIdempotent() {
        document(1, 1);
        chunk(11, 1, 1, 0, "退款以后不能继续学习购买课程");
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(sync.syncDue(index, 10)).isZero();
        assertThat(pgCount()).isEqualTo(1);
        assertThat(status(1, 1)).isEqualTo("INDEXED");
        assertThat(sync.currentVersions(10, List.of(101L))).containsExactlyEntriesOf(java.util.Map.of(1L, 1L));
        assertThat(sync.currentVersions(20, List.of(101L))).isEmpty();
        assertThat(sync.currentVersions(10, List.of(102L))).isEmpty();

        mysql.update("UPDATE knowledge_document SET source_version = 2, indexed_version = 2 WHERE id = 1");
        chunk(12, 1, 2, 0, "新版退款政策需要管理员审核");
        assertThat(sync.currentVersions(10, List.of(101L))).isEmpty();
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(pgCount()).isEqualTo(1);
        assertThat(index.search(10, List.of(101L), sync.currentVersions(10, List.of(101L)), "退款审核", 10))
                .hasSize(1).allMatch(hit -> hit.sourceVersion() == 2 && hit.chunkId() == 12);
        postgres.execute("TRUNCATE learnhub_rag.course_embeddings_bge_zh_v1");
        assertThat(sync.requeueAll()).isEqualTo(1);
        assertThat(sync.currentVersions(10, List.of(101L))).isEmpty();
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(pgCount()).isEqualTo(1);
    }

    @Test
    void versionChangeDuringUpsertCannotCompleteOldGenerationOrDeleteTheNewOne() {
        document(1, 1);
        chunk(11, 1, 1, 0, "旧版第一节课程讲义");
        chunk(12, 1, 1, 1, "旧版第二节课程讲义");
        CourseVectorIndex racing = spy(index);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            mysql.update("UPDATE knowledge_document SET source_version = 2, indexed_version = 2 WHERE id = 1");
            chunk(21, 1, 2, 0, "最新课程讲义与新的学习安排");
            return null;
        }).when(racing).upsert(any(), any());
        assertThat(sync.syncDue(racing, 10)).isZero();
        assertThat(status(1, 1)).isEqualTo("PENDING");
        assertThat(pgCount()).isEqualTo(1); // 第二片之前复核发现代次变化，停止旧代次。
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(pgCount()).isEqualTo(1);
        assertThat(index.search(10, List.of(101L), sync.currentVersions(10, List.of(101L)), "课程讲义", 10))
                .hasSize(1).allMatch(hit -> hit.sourceVersion() == 2 && hit.chunkId() == 21);
    }

    @Test
    void logicalDeletionAtSameVersionRemovesPgAndMarksRemoved() {
        document(1, 1);
        chunk(11, 1, 1, 0, "待删除课程资料");
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        mysql.update("UPDATE knowledge_document SET deleted = 1 WHERE id = 1");
        assertThat(sync.currentVersions(10, List.of(101L))).isEmpty();
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(status(1, 1)).isEqualTo("REMOVED");
        assertThat(pgCount()).isZero();
        assertThat(sync.syncDue(index, 10)).isZero();
    }

    @Test
    void pgFailureRecordsSafeCodeAndRetriesOnlyAfterBackoff() {
        document(1, 1);
        chunk(11, 1, 1, 0, "待重试学习资料");
        CourseVectorIndex failing = spy(index);
        doThrow(new IllegalStateException("controlled-failure-private-body"))
                .when(failing).upsert(any(), any());
        assertThat(sync.syncDue(failing, 10)).isZero();
        assertThat(status(1, 1)).isEqualTo("FAILED");
        assertThat(mysql.queryForObject("SELECT attempts FROM knowledge_vector_projection WHERE document_id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(mysql.queryForObject("SELECT last_error FROM knowledge_vector_projection WHERE document_id = 1",
                String.class)).isEqualTo("VECTOR_PROJECTION_FAILED");
        assertThat(sync.syncDue(index, 10)).isZero();
        mysql.update("UPDATE knowledge_vector_projection SET next_retry_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))");
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(status(1, 1)).isEqualTo("INDEXED");
        assertThat(pgCount()).isEqualTo(1);
    }

    @Test
    void crashAfterIndexedCasResumesPendingCleanupWithoutReembedding() {
        document(1, 1);
        chunk(11, 1, 1, 0, "旧代次待清理课程资料");
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        mysql.update("UPDATE knowledge_document SET source_version = 2, indexed_version = 2 WHERE id = 1");
        chunk(21, 1, 2, 0, "新代次课程资料");
        CourseVectorIndex interrupted = spy(index);
        // Error 模拟完成 CAS 后突然退出，越过普通失败补偿，保留真实的崩溃窗口状态。
        doThrow(new AssertionError("simulated process interruption"))
                .when(interrupted).removeOlderVersions(anyLong(), anyLong());
        assertThatThrownBy(() -> sync.syncDue(interrupted, 10)).isInstanceOf(AssertionError.class);
        assertThat(status(1, 2)).isEqualTo("INDEXED");
        assertThat(mysql.queryForObject("SELECT cleanup_pending FROM knowledge_vector_projection WHERE document_id = 1 AND source_version = 2",
                Integer.class)).isEqualTo(1);
        assertThat(pgCount()).isEqualTo(2);
        long encoded = embedding.embeddingCount();
        assertThat(sync.syncDue(index, 10)).isEqualTo(1);
        assertThat(embedding.embeddingCount()).isEqualTo(encoded);
        assertThat(pgCount()).isEqualTo(1);
        assertThat(mysql.queryForObject("SELECT cleanup_pending FROM knowledge_vector_projection WHERE document_id = 1 AND source_version = 2",
                Integer.class)).isZero();
    }

    @Test
    void oversizedCurrentDocumentScopeRejectsAllInsteadOfTruncating() {
        List<Object[]> documents = new java.util.ArrayList<>();
        List<Object[]> projections = new java.util.ArrayList<>();
        for (long id = 1; id <= 1001; id++) {
            documents.add(new Object[]{id, "owned-" + id, "范围测试", 10L, 101L, "a".repeat(64), 1L, 1L});
            projections.add(new Object[]{id, 1L, BgeChineseEmbedding.MODEL_ID, "INDEXED", 0});
        }
        mysql.batchUpdate("""
                INSERT INTO knowledge_document
                    (id, source_key, title, course_id, lesson_id, content_hash, source_version, indexed_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, documents);
        mysql.batchUpdate("""
                INSERT INTO knowledge_vector_projection (document_id, source_version, model_id, status, cleanup_pending)
                VALUES (?, ?, ?, ?, ?)
                """, projections);
        assertThatThrownBy(() -> sync.currentVersions(10, List.of(101L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1000");
        mysql.update("UPDATE knowledge_document SET deleted = 1 WHERE id = 1001");
        assertThat(sync.currentVersions(10, List.of(101L))).hasSize(1000);
    }

    @Test
    void frozenDevelopmentSetComparesBm25AndHybridSourceRecallWithoutModelApi() throws Exception {
        evaluateFrozenSet("docs/assistant-retrieval-development.json", "DEVELOPMENT", 8);
    }

    @Test
    void frozenThirtyQuestionHoldoutMeasuresRetrievalAndAuthorizationWithoutModelApi() throws Exception {
        evaluateFrozenSet("docs/assistant-retrieval-holdout-20261019.json", "HOLDOUT", 30);
    }

    private void evaluateFrozenSet(String fixture, String split, int expectedCases) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode catalog = mapper.readTree(Files.readString(projectFile("scripts/course-media/catalog.json")));
        JsonNode mapping = mapper.readTree(Files.readString(projectFile("scripts/assistant/stage-a-mapping.json")));
        byte[] frozenBytes = Files.readAllBytes(projectFile(fixture));
        JsonNode development = mapper.readTree(frozenBytes);
        assertThat(mapping.path("reviewed").asBoolean()).isTrue();
        assertThat(development.path("split").asText()).isEqualTo(split);
        assertThat(development.withArray("cases").size()).isEqualTo(expectedCases);

        Map<String, DevelopmentCourse> courses = new LinkedHashMap<>();
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        mapping.withArray("mappings").forEach(item -> grouped
                .computeIfAbsent(item.path("courseId").asText(), ignored -> new ArrayList<>()).add(item));
        for (var group : grouped.entrySet()) {
            courses.put(group.getKey(), insertDevelopmentCourse(group.getValue()));
        }
        KnowledgeStore store = context.getBean(KnowledgeStore.class);
        int scenes = 0;
        for (JsonNode item : mapping.withArray("mappings")) {
            JsonNode lesson = catalogLesson(catalog, item.path("courseSlug").asText(), item.path("lessonTitle").asText());
            List<ImportDocumentRequest.Scene> content = new ArrayList<>();
            for (JsonNode scene : lesson.withArray("scenes")) {
                String narration = scene.path("narration").asText().trim();
                content.add(new ImportDocumentRequest.Scene(scene.path("heading").asText().trim(),
                        narration.isEmpty() ? scene.path("body").asText().trim() : narration));
            }
            DevelopmentCourse course = courses.get(item.path("courseId").asText());
            store.importDocument(new ImportDocumentRequest(item.path("sourceKey").asText(),
                    lesson.path("title").asText(), course.id(), course.lessons().get(item.path("lessonTitle").asText()), content));
            scenes += content.size();
        }
        assertThat(scenes).isEqualTo(18);
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class)).isEqualTo(6);

        // 使用现有清理 API 认可的自有前缀，finally 只删除本测试创建的真实索引。
        String alias = "knowledge-it-vector-" + UUID.randomUUID().toString().replace("-", "");
        try (RestClient client = RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build()) {
            @SuppressWarnings("unchecked") ObjectProvider<RestClient> clients = mock(ObjectProvider.class);
            when(clients.getIfAvailable()).thenReturn(client);
            var gateway = new KnowledgeSearchGateway(clients, mapper, alias, "ik_max_word", "ik_smart");
            try {
                assertThat(new KnowledgeIndexSyncService(store, gateway, mysql).syncDue(20)).isEqualTo(6);
                gateway.refresh();
                long projectionBefore = embedding.embeddingCount();
                assertThat(sync.syncDue(index, 20)).isEqualTo(6);
                long projectionEmbeddings = embedding.embeddingCount() - projectionBefore;
                CourseService permissions = mock(CourseService.class);
                for (DevelopmentCourse course : courses.values()) {
                    when(permissions.readableLessonIds(7L, course.id())).thenReturn(List.copyOf(course.lessons().values()));
                }
                KnowledgeVectorSearch vectorSearch = new KnowledgeVectorSearch() {
                    public boolean hybridEnabled() { return true; }
                    public List<KnowledgeSearchGateway.Hit> search(long courseId, List<Long> readable, String question, int limit) {
                        return index.search(courseId, readable, sync.currentVersions(courseId, readable), question, limit);
                    }
                };
                @SuppressWarnings("unchecked") ObjectProvider<KnowledgeVectorSearch> vectors = mock(ObjectProvider.class);
                when(vectors.getIfAvailable()).thenReturn(vectorSearch);
                var bm25 = new KnowledgeRetrievalService(store, gateway, permissions);
                var hybrid = new KnowledgeRetrievalService(store, gateway, permissions, vectors, 0);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (JsonNode testCase : development.withArray("cases")) {
                    DevelopmentCourse course = courses.get(testCase.path("courseId").asText());
                    long owner = testCase.path("owner").asLong(7);
                    boolean denied = testCase.path("denyAccess").asBoolean() || owner != 7;
                    when(permissions.readableLessonIds(owner, course.id())).thenReturn(
                            denied ? List.of() : List.copyOf(course.lessons().values()));
                    rows.add(measureDevelopmentCase("BM25", bm25, testCase, course));
                    rows.add(measureDevelopmentCase("HYBRID", hybrid, testCase, course));
                }
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("schemaVersion", 1);
                report.put("split", split);
                report.put("fixtureSha256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(frozenBytes)));
                report.put("metricBoundary", "SOURCE_RECALL_PROXY_NOT_GENERATED_ANSWER_QUALITY; PERMISSIONS_MOCK; MYSQL_ES_PG_BGE_REAL");
                report.put("supplierCalls", 0);
                report.put("embeddingModel", BgeChineseEmbedding.MODEL_ID);
                report.put("projectionEmbeddingCount", projectionEmbeddings);
                report.put("cases", rows);
                report.put("summary", List.of(developmentSummary("BM25", rows), developmentSummary("HYBRID", rows)));
                Path directory = Path.of("target", "vector-evaluation");
                Files.createDirectories(directory);
                Path reportPath = directory.resolve(split.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID() + ".json");
                Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                System.out.println("FROZEN_RETRIEVAL_REPORT " + reportPath.toAbsolutePath());
                System.out.println("FROZEN_RETRIEVAL_SUMMARY " + split + " " + mapper.writeValueAsString(report.get("summary")));
                // 首次实际分数先保存，再断言权限边界；不要求混合召回一定优于 BM25。
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.get("scopeViolationCount")).isEqualTo(0L);
                    assertThat(row.get("forbiddenSourceCount")).isEqualTo(0L);
                    assertThat(row.get("authorizationViolationCount")).isEqualTo(0L);
                    assertThat(row.get("actualMode")).isEqualTo(Boolean.TRUE.equals(row.get("accessDenied")) ? "NONE" : row.get("mode"));
                });
            } finally {
                gateway.deleteOwnedIndex();
            }
        }
    }

    private Map<String, Object> measureDevelopmentCase(String mode, KnowledgeRetrievalService retrieval,
                                                       JsonNode testCase, DevelopmentCourse course) {
        long beforeEmbeddings = embedding.embeddingCount();
        long started = System.nanoTime();
        long owner = testCase.path("owner").asLong(7);
        boolean denied = testCase.path("denyAccess").asBoolean() || owner != 7;
        var result = retrieval.retrieve(owner, course.id(), testCase.path("query").asText(), 5);
        long elapsed = System.nanoTime() - started;
        List<String> actualKeys = result.sources().stream().map(source -> mysql.queryForObject(
                "SELECT source_key FROM knowledge_document WHERE id = ?", String.class,
                Long.parseLong(source.documentId()))).distinct().toList();
        List<String> expected = jsonStrings(testCase.withArray("expectedSourceKeys"));
        List<String> forbidden = jsonStrings(testCase.withArray("forbiddenSourceKeys"));
        boolean answerable = "ANSWERABLE".equals(testCase.path("category").asText());
        long matched = expected.stream().filter(actualKeys::contains).count();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", testCase.path("id").asText());
        row.put("mode", mode);
        row.put("actualMode", result.retrievalMode());
        row.put("category", testCase.path("category").asText());
        row.put("owner", owner);
        row.put("accessDenied", denied);
        row.put("authorizationViolationCount", denied ? (long) result.sources().size() : 0L);
        row.put("answerable", answerable);
        row.put("recallAt5", answerable && !expected.isEmpty() ? (double) matched / expected.size() : null);
        row.put("returnedChunkCount", result.sources().size());
        row.put("returnedSourceCount", actualKeys.size());
        row.put("noEvidenceFalseRecallCount", answerable ? 0 : actualKeys.size());
        row.put("scopeViolationCount", result.sources().stream().filter(source ->
                !source.courseId().equals(Long.toString(course.id()))
                        || !course.lessons().containsValue(Long.parseLong(source.lessonId()))).count());
        row.put("forbiddenSourceCount", actualKeys.stream().filter(forbidden::contains).count());
        row.put("latencyNanos", elapsed);
        row.put("embeddingCount", embedding.embeddingCount() - beforeEmbeddings);
        return row;
    }

    private Map<String, Object> developmentSummary(String mode, List<Map<String, Object>> rows) {
        List<Map<String, Object>> selected = rows.stream().filter(row -> mode.equals(row.get("mode"))).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode);
        result.put("caseCount", selected.size());
        result.put("answerableCount", selected.stream().filter(row -> Boolean.TRUE.equals(row.get("answerable"))).count());
        result.put("meanRecallAt5", selected.stream().filter(row -> row.get("recallAt5") != null)
                .mapToDouble(row -> (Double) row.get("recallAt5")).average().orElse(0));
        result.put("noEvidenceFalseRecallCount", selected.stream()
                .mapToInt(row -> (Integer) row.get("noEvidenceFalseRecallCount")).sum());
        result.put("meanLatencyMillis", selected.stream().mapToLong(row -> (Long) row.get("latencyNanos"))
                .average().orElse(0) / 1_000_000.0);
        result.put("embeddingCount", selected.stream().mapToLong(row -> (Long) row.get("embeddingCount")).sum());
        return result;
    }

    private DevelopmentCourse insertDevelopmentCourse(List<JsonNode> mappings) {
        long category = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long course = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long chapter = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        mysql.update("INSERT INTO course_category (id, name, sort_order, status) VALUES (?, ?, 1, 'ENABLED')",
                category, "向量开发集-" + course);
        mysql.update("INSERT INTO course (id, category_id, title, instructor, price, status, published_at) "
                + "VALUES (?, ?, ?, 'integration-test', 1.00, 'PUBLISHED', CURRENT_TIMESTAMP(3))",
                course, category, "向量开发集-" + course);
        mysql.update("INSERT INTO course_chapter (id, course_id, title, sort_order) VALUES (?, ?, '开发集章节', 1)",
                chapter, course);
        Map<String, Long> lessons = new LinkedHashMap<>();
        for (JsonNode item : mappings) {
            long lesson = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            String title = item.path("lessonTitle").asText();
            mysql.update("INSERT INTO course_lesson (id, chapter_id, title, duration_seconds, free_preview, sort_order) "
                    + "VALUES (?, ?, ?, 60, 0, ?)", lesson, chapter, title, lessons.size() + 1);
            assertThat(lessons.put(title, lesson)).isNull();
        }
        return new DevelopmentCourse(course, Map.copyOf(lessons));
    }

    private JsonNode catalogLesson(JsonNode catalog, String slug, String title) {
        for (JsonNode course : catalog) {
            if (!slug.equals(course.path("slug").asText())) continue;
            for (JsonNode lesson : course.withArray("lessons")) {
                if (title.equals(lesson.path("title").asText())) return lesson;
            }
        }
        throw new IllegalStateException("冻结开发集映射未找到讲义");
    }

    private List<String> jsonStrings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private record DevelopmentCourse(long id, Map<String, Long> lessons) {}

    private void document(long id, long version) {
        mysql.update("""
                INSERT INTO knowledge_document
                    (id, source_key, title, course_id, lesson_id, content_hash, source_version, indexed_version)
                VALUES (?, ?, '隔离测试材料', 10, 101, ?, ?, ?)
                """, id, "owned-" + id, "a".repeat(64), version, version);
    }

    private void chunk(long id, long document, long version, int order, String body) {
        mysql.update("""
                INSERT INTO knowledge_chunk (id, document_id, source_version, chunk_order, heading, content)
                VALUES (?, ?, ?, ?, '隔离测试小节', ?)
                """, id, document, version, order, body);
    }

    private String status(long document, long version) {
        return mysql.queryForObject("SELECT status FROM knowledge_vector_projection WHERE document_id = ? AND source_version = ? AND model_id = ?",
                String.class, document, version, BgeChineseEmbedding.MODEL_ID);
    }

    private int pgCount() {
        return postgres.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1", Integer.class);
    }

    private String requiredPort(String key) {
        String value = System.getenv(key);
        if (value == null || !value.matches("[0-9]+")) throw new IllegalStateException("Owned test port required");
        return value;
    }

    private String requiredPassword(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Owned test credential required");
        return value;
    }

    private Path projectFile(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("..").resolve(relative);
    }
}
