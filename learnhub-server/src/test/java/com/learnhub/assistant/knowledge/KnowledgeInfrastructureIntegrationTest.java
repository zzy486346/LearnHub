package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import com.learnhub.search.SearchUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "learnhub.knowledge.it.enabled", matches = "true")
class KnowledgeInfrastructureIntegrationTest {
    private static final String DATABASE_PREFIX = "learnhub_knowledge_it_";
    private static final String ALIAS_PREFIX = "knowledge-it-";
    private static final String ADMIN_USERNAME_ENV = "LEARNHUB_KNOWLEDGE_IT_MYSQL_ADMIN_USERNAME";
    private static final String ADMIN_PASSWORD_ENV = "LEARNHUB_KNOWLEDGE_IT_MYSQL_ADMIN_PASSWORD";

    private String databaseName;
    private String serverUrl;
    private DatabaseCredentials credentials;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private KnowledgeStore store;
    private RestClient elasticsearch;
    private KnowledgeSearchGateway gateway;

    @BeforeAll
    void createIsolatedDatabase() throws Exception {
        databaseName = DATABASE_PREFIX + randomToken();
        serverUrl = mysqlServerUrl();
        credentials = databaseCredentials();
        try {
            createDatabase();
            DataSource dataSource = isolatedDataSource();
            initializeSchema(dataSource);
            startKnowledgeContext(dataSource);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(databaseName);
            elasticsearch = RestClient.builder(HttpHost.create(elasticsearchUri())).build();
        } catch (Exception error) {
            try {
                closeContextAndClient();
            } catch (Exception cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            dropDatabaseAfterFailure(error);
            throw error;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void createOwnedKnowledgeIndex() {
        ObjectProvider<RestClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(elasticsearch);
        String alias = ALIAS_PREFIX + randomToken();
        gateway = new KnowledgeSearchGateway(clients, new ObjectMapper().findAndRegisterModules(),
                alias, "ik_max_word", "ik_smart");
        assertThat(gateway.ensureAlias()).isTrue();
        assertThat(gateway.ensureAlias()).isFalse();
    }

    @AfterEach
    void cleanOwnedInfrastructure() {
        RuntimeException failure = null;
        if (gateway != null) {
            try {
                gateway.deleteOwnedIndex();
            } catch (RuntimeException error) {
                failure = new IllegalStateException("知识检索集成测试的 Elasticsearch 清理失败", error);
            } finally {
                gateway = null;
            }
        }
        if (jdbc != null) {
            try {
                jdbc.update("DELETE FROM knowledge_chunk");
                jdbc.update("DELETE FROM knowledge_document");
                jdbc.update("DELETE FROM course_lesson");
                jdbc.update("DELETE FROM course_chapter");
                jdbc.update("DELETE FROM course");
                jdbc.update("DELETE FROM course_category");
            } catch (RuntimeException error) {
                if (failure == null) failure = new IllegalStateException("知识检索集成测试的 MySQL 清理失败");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    @AfterAll
    void dropIsolatedDatabase() throws Exception {
        Exception failure = null;
        try {
            closeContextAndClient();
        } catch (Exception error) {
            failure = error;
        }
        try {
            dropDatabase();
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    @Test
    void importsAreIdempotentAndOnlyCurrentIndexedVersionCanBeRead() {
        CourseFixture fixture = insertCourseFixture();
        var request = document("store-idempotency", fixture, "第一版",
                "倒排索引记录词项到文档的映射。", "BM25 根据词频和文档长度计算相关性。");

        var first = store.importDocument(request);
        var repeated = store.importDocument(request);

        assertThat(first.documentId()).isEqualTo(repeated.documentId());
        assertThat(first.sourceVersion()).isEqualTo(1);
        assertThat(first.chunkCount()).isEqualTo(2);
        assertThat(first.unchanged()).isFalse();
        assertThat(repeated.unchanged()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class)).isEqualTo(2);

        long documentId = Long.parseLong(first.documentId());
        long oldChunkId = store.chunks(documentId, 1).get(0).id();
        assertThat(store.markIndexed(documentId, 1)).isTrue();
        assertThat(store.currentChunk(oldChunkId)).isPresent();

        var updated = store.importDocument(document("store-idempotency", fixture, "第二版",
                "倒排索引先定位候选文档，再由 BM25 计算相关性。"));

        assertThat(updated.sourceVersion()).isEqualTo(2);
        assertThat(store.currentChunk(oldChunkId)).isEmpty();
        assertThat(store.markFailed(documentId, 1)).isFalse();
        assertThat(store.markIndexed(documentId, 1)).isFalse();
        long currentChunkId = store.chunks(documentId, 2).get(0).id();
        assertThat(store.currentChunk(currentChunkId)).isEmpty();
        assertThat(store.markIndexed(documentId, 2)).isTrue();
        assertThat(store.currentChunk(currentChunkId)).isPresent();
    }

    @Test
    void invalidCourseLessonBindingRollsBackWithoutCreatingKnowledgeRows() {
        CourseFixture fixture = insertCourseFixture();
        var mismatched = new ImportDocumentRequest("invalid-binding", "错误归属", fixture.courseId(),
                fixture.lessonId() + 1, List.of(new ImportDocumentRequest.Scene("场景", "不可写入的正文")));

        assertThatThrownBy(() -> store.importDocument(mismatched))
                .isInstanceOf(BusinessException.class)
                .hasMessage("课程或课时不存在，或课时不属于指定课程");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class)).isZero();
    }

    @Test
    void updatedDeletedAndRebuiltIndexCannotServeObsoleteContent() {
        CourseFixture fixture = insertCourseFixture();
        var imported = store.importDocument(document("version-lifecycle", fixture, "版本变化", "legacyterm 历史内容"));
        long documentId = Long.parseLong(imported.documentId());
        var sync = new KnowledgeIndexSyncService(store, gateway, jdbc);
        assertThat(sync.syncDue(10)).isEqualTo(1);
        assertThat(gateway.search(fixture.courseId(), List.of(fixture.lessonId()), "legacyterm", 10)).hasSize(1);

        store.importDocument(document("version-lifecycle", fixture, "版本变化", "currentterm 当前内容"));
        assertThat(sync.syncDue(10)).isEqualTo(1);
        assertThat(gateway.search(fixture.courseId(), List.of(fixture.lessonId()), "legacyterm", 10)).isEmpty();
        var current = gateway.search(fixture.courseId(), List.of(fixture.lessonId()), "currentterm", 10);
        assertThat(current).singleElement().satisfies(hit -> assertThat(hit.sourceVersion()).isEqualTo(2));

        jdbc.update("UPDATE knowledge_document SET deleted = 1 WHERE id = ?", documentId);
        var courses = mock(com.learnhub.course.service.CourseService.class);
        when(courses.readableLessonIds(7L, fixture.courseId())).thenReturn(List.of(fixture.lessonId()));
        var retrieval = new KnowledgeRetrievalService(store, gateway, courses);
        assertThat(retrieval.retrieve(7L, fixture.courseId(), "currentterm", 5).sources()).isEmpty();

        jdbc.update("UPDATE knowledge_document SET deleted = 0 WHERE id = ?", documentId);
        gateway.deleteOwnedIndex();
        assertThat(sync.syncDue(10)).isEqualTo(1);
        assertThat(retrieval.retrieve(7L, fixture.courseId(), "currentterm", 5).sources()).hasSize(1);
    }

    @Test
    void concurrentDuplicateImportsShareOneDocumentAndOneSetOfChunks() throws Exception {
        var fixture = insertCourseFixture();
        var request = document("concurrent-import", fixture, "并发导入", "同一份讲义只建立一组知识块");
        var snapshotReady = new java.util.concurrent.CyclicBarrier(2);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        java.util.concurrent.Callable<ImportDocumentResult> importTask = () -> transaction.execute(status -> {
            // 两个事务先建立 RR 快照，再竞争同一个来源锁，验证等待者使用当前读。
            jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class);
            try { snapshotReady.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception error) { throw new IllegalStateException("并发测试同步失败", error); }
            return store.importDocument(request);
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(importTask);
            var second = executor.submit(importTask);
            var firstResult = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var secondResult = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(firstResult.documentId()).isEqualTo(secondResult.documentId());
            assertThat(firstResult.unchanged()).isNotEqualTo(secondResult.unchanged());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void knowledgeMigrationCanRunTwiceAndPreservesSchemaComments() throws Exception {
        executeKnowledgeMigration();
        String documentDefinition = showCreateTable("knowledge_document");
        String chunkDefinition = showCreateTable("knowledge_chunk");

        executeKnowledgeMigration();

        assertThat(showCreateTable("knowledge_document")).isEqualTo(documentDefinition);
        assertThat(showCreateTable("knowledge_chunk")).isEqualTo(chunkDefinition);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME IN ('knowledge_document', 'knowledge_chunk')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT TABLE_COMMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
                """, String.class)).isEqualTo("课程课时知识文档表");
        assertThat(jdbc.queryForObject("""
                SELECT TABLE_COMMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_chunk'
                """, String.class)).isEqualTo("知识文档版本片段表");
        assertThat(columnComment("knowledge_document", "source_key"))
                .isEqualTo("外部知识来源稳定标识，同一来源全局唯一");
        assertThat(columnComment("knowledge_document", "indexed_version"))
                .isEqualTo("已完整写入并刷新知识索引的来源版本号");
        assertThat(columnComment("knowledge_chunk", "content"))
                .isEqualTo("知识片段正文，最多六百个Unicode代码点");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document'
                  AND INDEX_NAME = 'uk_knowledge_document_source'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_chunk'
                  AND INDEX_NAME = 'uk_knowledge_chunk_order'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void syncIndexesCurrentVersionAndFiltersSearchByAuthorizedLessons() {
        CourseFixture fixture = insertCourseFixture();
        CourseFixture forbidden = insertCourseFixture();
        var imported = store.importDocument(document("sync-search", fixture, "相关性排序",
                "BM25 结合词频、逆文档频率与文档长度进行相关性排序。"));
        store.importDocument(document("sync-distractor", fixture, "搜索背景资料",
                "课程搜索需要先理解需求。本段只简要提到 BM25，重点是产品界面与交互设计。"));
        store.importDocument(new ImportDocumentRequest("sync-null-heading", "无场景标题材料",
                fixture.courseId(), fixture.lessonId(), List.of(
                        new ImportDocumentRequest.Scene(null, "没有场景标题的知识片段也必须能够写入索引。"))));
        store.importDocument(document("sync-forbidden", forbidden, "未授权正文",
                "BM25 私有调参资料不应返回给其他课时。"));
        var sync = new KnowledgeIndexSyncService(store, gateway, jdbc);

        assertThat(sync.syncDue(10)).isEqualTo(4);
        gateway.refresh();

        var hits = gateway.search(fixture.courseId(), List.of(fixture.lessonId()), "BM25 相关性", 10);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).documentId()).isEqualTo(Long.parseLong(imported.documentId()));
        assertThat(hits.get(0).sourceVersion()).isEqualTo(1);
        assertThat(gateway.search(fixture.courseId(), List.of(forbidden.lessonId()), "BM25", 10)).isEmpty();
        assertThat(gateway.search(forbidden.courseId(), List.of(forbidden.lessonId()), "BM25", 10)).hasSize(1);

        JsonNode mapping = elasticsearchJson("GET", "/" + gateway.aliasName() + "/_mapping", null);
        JsonNode title = mapping.elements().next().path("mappings").path("properties").path("title");
        assertThat(title.path("analyzer").asText()).isEqualTo("ik_max_word");
        assertThat(title.path("search_analyzer").asText()).isEqualTo("ik_smart");
        JsonNode analyzed = elasticsearchJson("POST", "/" + gateway.aliasName() + "/_analyze",
                Map.of("field", "title", "text", "中华人民共和国国歌"));
        Set<String> tokens = new java.util.HashSet<>();
        analyzed.path("tokens").forEach(token -> tokens.add(token.path("token").asText()));
        assertThat(tokens).contains("中华人民共和国", "国歌");
    }

    @Test
    void failedSyncIsPersistedAndCanBeRetriedWithoutAnOldVersionWinning() {
        CourseFixture fixture = insertCourseFixture();
        var first = store.importDocument(document("retry-version", fixture, "第一版", "缓存穿透可以缓存空值。"));
        KnowledgeSearchGateway retryGateway = spy(gateway);
        doThrow(new SearchUnavailableException(new IOException("simulated Elasticsearch outage")))
                .when(retryGateway).index(any(), any());
        var failingSync = new KnowledgeIndexSyncService(store, retryGateway, jdbc);

        assertThat(failingSync.syncDue(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT index_status FROM knowledge_document WHERE id = ?",
                String.class, Long.parseLong(first.documentId()))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_document WHERE id = ?",
                Integer.class, Long.parseLong(first.documentId()))).isEqualTo(1);

        var second = store.importDocument(document("retry-version", fixture, "第二版",
                "缓存穿透可以缓存空值，也可以使用布隆过滤器。"));
        assertThat(second.sourceVersion()).isEqualTo(2);
        assertThat(store.markIndexed(Long.parseLong(first.documentId()), 1)).isFalse();

        doCallRealMethod().when(retryGateway).index(any(), any());
        var retry = new KnowledgeIndexSyncService(store, retryGateway, jdbc);
        assertThat(retry.syncDue(10)).isEqualTo(1);
        gateway.refresh();
        var hits = gateway.search(fixture.courseId(), List.of(fixture.lessonId()), "布隆过滤器", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sourceVersion()).isEqualTo(2);
    }

    @Test
    void splitterStrategyUpgradeKeepsOldChunksAuditableButInvalidatesOldEvidenceAndIndexHits() {
        CourseFixture fixture = insertCourseFixture();
        var request = document("strategy-upgrade", fixture, "切分策略升级",
                "strategylegacyterm 用于验证相同正文按新策略重新切分。",
                "审计要求旧版本切片保留，但不能继续作为当前证据。 ");
        var first = store.importDocument(request);
        long documentId = Long.parseLong(first.documentId());
        var oldChunks = store.chunks(documentId, first.sourceVersion());
        var sync = new KnowledgeIndexSyncService(store, gateway, jdbc);
        assertThat(sync.syncDue(10)).isEqualTo(1);
        gateway.refresh();

        CourseService courses = mock(CourseService.class);
        when(courses.readableLessonIds(7L, fixture.courseId())).thenReturn(List.of(fixture.lessonId()));
        var retrieval = new KnowledgeRetrievalService(store, gateway, courses);
        var oldSource = retrieval.retrieve(7L, fixture.courseId(), "strategylegacyterm", 5).sources().get(0);
        var oldEvidence = new EvidenceBook(7L, fixture.courseId(), store, courses,
                new ObjectMapper().findAndRegisterModules());
        oldEvidence.material(oldSource);
        oldEvidence.check();

        assertThat(jdbc.update("UPDATE knowledge_document SET content_hash = ? WHERE id = ?",
                "a".repeat(64), documentId)).isEqualTo(1);
        var upgraded = store.importDocument(request);
        var newChunks = store.chunks(documentId, upgraded.sourceVersion());

        assertThat(upgraded.documentId()).isEqualTo(first.documentId());
        assertThat(upgraded.sourceVersion()).isEqualTo(first.sourceVersion() + 1);
        assertThat(newChunks).extracting(KnowledgeChunkRecord::id)
                .doesNotContainAnyElementsOf(oldChunks.stream().map(KnowledgeChunkRecord::id).toList());
        assertThat(store.chunks(documentId, first.sourceVersion())).isEqualTo(oldChunks);
        assertThat(store.currentChunks(oldChunks.stream().map(KnowledgeChunkRecord::id).toList())).isEmpty();

        assertThat(sync.syncDue(10)).isEqualTo(1);
        gateway.refresh();
        var currentHits = gateway.search(fixture.courseId(), List.of(fixture.lessonId()),
                "strategylegacyterm", 10);
        assertThat(currentHits).isNotEmpty().allSatisfy(hit -> {
            assertThat(hit.sourceVersion()).isEqualTo(upgraded.sourceVersion());
            assertThat(oldChunks).extracting(KnowledgeChunkRecord::id).doesNotContain(hit.chunkId());
        });
        assertThatThrownBy(oldEvidence::check)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_EVIDENCE_CHANGED"));
    }

    @Test
    void reviewedCatalogDevelopmentSetRunsAgainstRemappedIsolatedCourses() throws Exception {
        JsonNode catalog = readRepositoryJson("scripts/course-media/catalog.json");
        JsonNode mapping = readRepositoryJson("scripts/assistant/stage-a-mapping.json");
        JsonNode development = readRepositoryJson("docs/assistant-retrieval-development.json");
        assertThat(mapping.path("reviewed").asBoolean()).isTrue();
        assertThat(development.path("split").asText()).isEqualTo("DEVELOPMENT");

        Map<String, CatalogLesson> catalogLessons = catalogLessons(catalog);
        Map<String, List<JsonNode>> mappingsByOriginalCourse = new LinkedHashMap<>();
        mapping.withArray("mappings").forEach(item -> mappingsByOriginalCourse
                .computeIfAbsent(item.path("courseId").asText(), ignored -> new ArrayList<>()).add(item));
        assertThat(mappingsByOriginalCourse).hasSize(3);
        assertThat(mappingsByOriginalCourse.values()).allSatisfy(items -> {
            assertThat(items).hasSize(2);
            assertThat(items.stream().map(item -> item.path("courseSlug").asText()).distinct()).hasSize(1);
        });

        Map<String, DevelopmentCourse> remappedCourses = new LinkedHashMap<>();
        for (var group : mappingsByOriginalCourse.entrySet()) {
            String slug = group.getValue().get(0).path("courseSlug").asText();
            List<String> lessonTitles = group.getValue().stream()
                    .map(item -> item.path("lessonTitle").asText()).toList();
            remappedCourses.put(group.getKey(), insertDevelopmentCourse(slug, lessonTitles));
        }

        int importedScenes = 0;
        for (JsonNode item : mapping.withArray("mappings")) {
            String originalCourseId = item.path("courseId").asText();
            String lessonTitle = item.path("lessonTitle").asText();
            String slug = item.path("courseSlug").asText();
            DevelopmentCourse course = remappedCourses.get(originalCourseId);
            CatalogLesson lesson = catalogLessons.get(slug + "\u0000" + lessonTitle);
            assertThat(course).isNotNull();
            assertThat(lesson).isNotNull();
            Long lessonId = course.lessonIdsByTitle().get(lessonTitle);
            assertThat(lessonId).isNotNull();
            var result = store.importDocument(new ImportDocumentRequest(item.path("sourceKey").asText(),
                    lesson.title(), course.courseId(), lessonId, lesson.scenes()));
            assertThat(result.chunkCount()).isPositive();
            importedScenes += lesson.scenes().size();
        }
        assertThat(importedScenes).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class)).isEqualTo(6);
        var sync = new KnowledgeIndexSyncService(store, gateway, jdbc);
        assertThat(sync.syncDue(20)).isEqualTo(6);
        gateway.refresh();

        CourseService courses = mock(CourseService.class);
        for (DevelopmentCourse course : remappedCourses.values()) {
            when(courses.readableLessonIds(7L, course.courseId())).thenReturn(course.lessonIds());
        }
        var retrieval = new KnowledgeRetrievalService(store, gateway, courses);
        int executed = 0;
        for (JsonNode testCase : development.withArray("cases")) {
            DevelopmentCourse course = remappedCourses.get(testCase.path("courseId").asText());
            assertThat(course).as(testCase.path("id").asText()).isNotNull();
            var result = retrieval.retrieve(7L, course.courseId(), testCase.path("query").asText(), 5);
            List<String> actualSourceKeys = result.sources().stream().map(source -> jdbc.queryForObject(
                    "SELECT source_key FROM knowledge_document WHERE id = ?", String.class,
                    Long.parseLong(source.documentId()))).distinct().toList();
            List<String> expectedSourceKeys = strings(testCase.withArray("expectedSourceKeys"));
            List<String> expectedTerms = strings(testCase.withArray("expectedTerms"));
            List<String> forbiddenSourceKeys = strings(testCase.withArray("forbiddenSourceKeys"));
            if (!expectedSourceKeys.isEmpty()) {
                assertThat(actualSourceKeys).as(testCase.path("id").asText())
                        .containsAnyElementsOf(expectedSourceKeys);
            }
            if (!expectedTerms.isEmpty()) {
                String evidence = result.sources().stream().map(KnowledgeRetrievalService.Source::text)
                        .reduce("", (left, right) -> left + "\n" + right);
                assertThat(evidence).as(testCase.path("id").asText())
                        .contains(expectedTerms.toArray(String[]::new));
            }
            if (!forbiddenSourceKeys.isEmpty()) {
                assertThat(actualSourceKeys).as(testCase.path("id").asText())
                        .doesNotContainAnyElementsOf(forbiddenSourceKeys);
            }
            System.out.printf("STAGE4_DEV case=%s sourceCount=%d%n", testCase.path("id").asText(), actualSourceKeys.size());
            executed++;
        }
        assertThat(executed).isEqualTo(8);
    }

    private ImportDocumentRequest document(String sourceKey, CourseFixture fixture, String title, String... contents) {
        List<ImportDocumentRequest.Scene> scenes = java.util.stream.IntStream.range(0, contents.length)
                .mapToObj(index -> new ImportDocumentRequest.Scene("场景" + (index + 1), contents[index]))
                .toList();
        return new ImportDocumentRequest(sourceKey, title, fixture.courseId(), fixture.lessonId(), scenes);
    }

    private CourseFixture insertCourseFixture() {
        long categoryId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long courseId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long chapterId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long lessonId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO course_category (id, name, sort_order, status) VALUES (?, ?, 1, 'ENABLED')",
                categoryId, "知识测试分类-" + randomToken());
        jdbc.update("""
                INSERT INTO course (id, category_id, title, instructor, price, status, published_at)
                VALUES (?, ?, ?, 'integration-test', 1.00, 'PUBLISHED', CURRENT_TIMESTAMP(3))
                """, courseId, categoryId, "知识测试课程-" + randomToken());
        jdbc.update("INSERT INTO course_chapter (id, course_id, title, sort_order) VALUES (?, ?, '测试章节', 1)",
                chapterId, courseId);
        jdbc.update("""
                INSERT INTO course_lesson (id, chapter_id, title, duration_seconds, free_preview, sort_order)
                VALUES (?, ?, '测试课时', 60, 0, 1)
                """, lessonId, chapterId);
        return new CourseFixture(courseId, lessonId);
    }

    private DevelopmentCourse insertDevelopmentCourse(String slug, List<String> lessonTitles) {
        assertThat(lessonTitles).hasSize(2);
        long categoryId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long courseId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long chapterId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO course_category (id, name, sort_order, status) VALUES (?, ?, 1, 'ENABLED')",
                categoryId, "开发集分类-" + slug + "-" + randomToken());
        jdbc.update("""
                INSERT INTO course (id, category_id, title, instructor, price, status, published_at)
                VALUES (?, ?, ?, 'integration-test', 1.00, 'PUBLISHED', CURRENT_TIMESTAMP(3))
                """, courseId, categoryId, "开发集课程-" + slug + "-" + randomToken());
        jdbc.update("INSERT INTO course_chapter (id, course_id, title, sort_order) VALUES (?, ?, '开发集章节', 1)",
                chapterId, courseId);
        Map<String, Long> lessonIdsByTitle = new LinkedHashMap<>();
        int order = 1;
        for (String title : lessonTitles) {
            long lessonId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            jdbc.update("""
                    INSERT INTO course_lesson (id, chapter_id, title, duration_seconds, free_preview, sort_order)
                    VALUES (?, ?, ?, 60, 0, ?)
                    """, lessonId, chapterId, title, order++);
            assertThat(lessonIdsByTitle.put(title, lessonId)).isNull();
        }
        return new DevelopmentCourse(courseId, List.copyOf(lessonIdsByTitle.values()), Map.copyOf(lessonIdsByTitle));
    }

    private Map<String, CatalogLesson> catalogLessons(JsonNode catalog) {
        Map<String, CatalogLesson> result = new LinkedHashMap<>();
        for (JsonNode course : catalog) {
            String slug = course.path("slug").asText();
            for (JsonNode lesson : course.withArray("lessons")) {
                List<ImportDocumentRequest.Scene> scenes = new ArrayList<>();
                for (JsonNode scene : lesson.withArray("scenes")) {
                    String narration = scene.path("narration").asText().trim();
                    String content = narration.isEmpty() ? scene.path("body").asText().trim() : narration;
                    scenes.add(new ImportDocumentRequest.Scene(scene.path("heading").asText().trim(), content));
                }
                var value = new CatalogLesson(lesson.path("title").asText(), List.copyOf(scenes));
                assertThat(result.put(slug + "\u0000" + value.title(), value)).isNull();
            }
        }
        return result;
    }

    private JsonNode readRepositoryJson(String relativePath) throws IOException {
        for (Path candidate : List.of(Path.of(relativePath), Path.of("..").resolve(relativePath))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return new ObjectMapper().findAndRegisterModules().readTree(Files.readString(normalized));
            }
        }
        throw new IllegalStateException("找不到测试资源：" + relativePath);
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private JsonNode elasticsearchJson(String method, String path, Object body) {
        try {
            Request request = new Request(method, path);
            if (body != null) request.setJsonEntity(new ObjectMapper().writeValueAsString(body));
            return new ObjectMapper().readTree(EntityUtils.toString(elasticsearch.performRequest(request).getEntity()));
        } catch (IOException error) {
            throw new IllegalStateException("Elasticsearch 集成测试请求失败", error);
        }
    }

    private void executeKnowledgeMigration() throws Exception {
        String migration = Files.readString(migrationPath(), StandardCharsets.UTF_8)
                .replaceFirst("(?im)^USE learnhub;\\s*", "");
        if (migration.matches("(?is).*\\bUSE\\s+learnhub\\b.*")) {
            throw new IllegalStateException("知识迁移仍指向业务数据库，拒绝执行");
        }
        try (Connection connection = isolatedDataSource().getConnection()) {
            var resource = new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8),
                    "isolated copy of knowledge migration");
            ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private String showCreateTable(String tableName) {
        return jdbc.queryForObject("SHOW CREATE TABLE " + tableName,
                (result, row) -> result.getString(2));
    }

    private String columnComment(String tableName, String columnName) {
        return jdbc.queryForObject("""
                SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, String.class, tableName, columnName);
    }

    private void createDatabase() {
        try (Connection connection = DriverManager.getConnection(serverUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + databaseName
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException error) {
            throw new IllegalStateException("无法创建隔离测试数据库；请为测试账号授予 CREATE DATABASE 权限，或通过环境变量 "
                    + ADMIN_USERNAME_ENV + " 和 " + ADMIN_PASSWORD_ENV + " 提供专用测试管理员凭据"
                    + "（SQLState=" + error.getSQLState() + "，vendorCode=" + error.getErrorCode() + "）", error);
        }
    }

    private void initializeSchema(DataSource dataSource) throws IOException, SQLException {
        String schema = Files.readString(schemaPath(), StandardCharsets.UTF_8);
        String isolatedSchema = schema
                .replaceFirst("(?im)^CREATE DATABASE IF NOT EXISTS learnhub[^;]*;\\s*", "")
                .replaceFirst("(?im)^USE learnhub;\\s*", "");
        if (isolatedSchema.equals(schema)
                || isolatedSchema.matches("(?is).*\\b(?:CREATE\\s+DATABASE[^;]*|USE)\\s+learnhub\\b.*")) {
            throw new IllegalStateException("schema.sql 的数据库初始化前导发生变化，拒绝在非隔离数据库执行");
        }
        try (Connection connection = dataSource.getConnection()) {
            var resource = new ByteArrayResource(isolatedSchema.getBytes(StandardCharsets.UTF_8),
                    "isolated copy of sql/schema.sql");
            ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private void startKnowledgeContext(DataSource dataSource) {
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(KnowledgeJdbcRepository.class);
        context.registerBean(KnowledgeStore.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        store = context.getBean(KnowledgeStore.class);
    }

    private DataSource isolatedDataSource() {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(serverUrl.replace("/?", "/" + databaseName + "?"));
        dataSource.setUsername(credentials.username());
        dataSource.setPassword(credentials.password());
        return dataSource;
    }

    private void closeContextAndClient() throws IOException {
        if (context != null) {
            context.close();
            context = null;
            jdbc = null;
            store = null;
        }
        if (elasticsearch != null) {
            elasticsearch.close();
            elasticsearch = null;
        }
    }

    private void dropDatabaseAfterFailure(Exception setupFailure) {
        try {
            dropDatabase();
        } catch (Exception cleanupFailure) {
            setupFailure.addSuppressed(cleanupFailure);
        }
    }

    private void dropDatabase() throws SQLException {
        if (databaseName == null || !databaseName.matches("learnhub_knowledge_it_[a-f0-9]{24}")) {
            throw new IllegalStateException("拒绝删除非测试数据库");
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    private DatabaseCredentials databaseCredentials() {
        String adminUsername = trimToNull(System.getenv(ADMIN_USERNAME_ENV));
        String adminPassword = System.getenv(ADMIN_PASSWORD_ENV);
        if ((adminUsername == null) != (adminPassword == null)) {
            throw new IllegalStateException(ADMIN_USERNAME_ENV + " 与 " + ADMIN_PASSWORD_ENV + " 必须同时设置");
        }
        if (adminUsername != null) return new DatabaseCredentials(adminUsername, adminPassword);
        String username = trimToNull(System.getenv("LEARNHUB_DB_USERNAME"));
        String password = System.getenv("LEARNHUB_DB_PASSWORD");
        return new DatabaseCredentials(username == null ? "learnhub" : username,
                password == null ? "learnhub" : password);
    }

    private String mysqlServerUrl() {
        String host = environment("LEARNHUB_KNOWLEDGE_IT_MYSQL_HOST", "localhost");
        String port = environment("LEARNHUB_KNOWLEDGE_IT_MYSQL_PORT", "3306");
        return "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    private String elasticsearchUri() {
        return environment("LEARNHUB_KNOWLEDGE_IT_ES_URI",
                environment("LEARNHUB_ES_URIS", "http://localhost:9200"));
    }

    private Path schemaPath() {
        for (Path candidate : List.of(Path.of("sql", "schema.sql"), Path.of("..", "sql", "schema.sql"))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return normalized;
        }
        throw new IllegalStateException("找不到 sql/schema.sql，无法初始化隔离测试数据库");
    }

    private Path migrationPath() {
        for (Path candidate : List.of(
                Path.of("sql", "migrations", "V20261015__knowledge_documents.sql"),
                Path.of("..", "sql", "migrations", "V20261015__knowledge_documents.sql"))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return normalized;
        }
        throw new IllegalStateException("找不到知识文档迁移脚本");
    }

    private String environment(String name, String defaultValue) {
        String value = trimToNull(System.getenv(name));
        return value == null ? defaultValue : value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
                .substring(0, 24).toLowerCase(Locale.ROOT);
    }

    private record CourseFixture(long courseId, long lessonId) {
    }

    private record DevelopmentCourse(long courseId, List<Long> lessonIds, Map<String, Long> lessonIdsByTitle) {
    }

    private record CatalogLesson(String title, List<ImportDocumentRequest.Scene> scenes) {
    }

    private record DatabaseCredentials(String username, String password) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
