package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** 显式开启才会向本地配置指向的业务库导入资料；不启动 HTTP 后端、不调用模型。 */
@EnabledIfSystemProperty(named = "learnhub.assistant.catalog.import", matches = "true")
@ContextConfiguration(initializers = CatalogMaterialImportIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.ai.mode=DISABLED"
})
class CatalogMaterialImportIntegrationTest {
    // 禁用该测试上下文的所有定时任务及两个业务启动迁移器，不影响用户运行中的应用。
    @MockBean LikeLegacyKeyMigration legacyLikeMigration;
    @MockBean LikeSequenceService likeSequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeStore store;
    @Autowired KnowledgeIndexSyncService sync;
    @Autowired KnowledgeSearchGateway search;
    @Autowired KnowledgeRetrievalService retrieval;
    @Autowired CourseService courses;

    static class LocalOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            var environment = context.getEnvironment();
            String jdbc = environment.getProperty("spring.datasource.url", "");
            String elastic = environment.getProperty("spring.elasticsearch.uris", "");
            String redis = environment.getProperty("spring.data.redis.host", "");
            if (!jdbc.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/learnhub(?:\\?.*)?")
                    || !elastic.matches("http://(localhost|127\\.0\\.0\\.1):9200/?")
                    || !List.of("localhost", "127.0.0.1").contains(redis)) {
                throw new IllegalStateException("导入仅允许本机 MySQL learnhub 库、ES 9200 和 Redis，拒绝启动上下文");
            }
            // 配置类扫描完成后、任何普通单例创建前移除调度基础设施，避免首轮即执行的 @Scheduled。
            context.addBeanFactoryPostProcessor(factory -> {
                String scheduling = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                if (factory.containsBeanDefinition(scheduling))
                    ((BeanDefinitionRegistry) factory).removeBeanDefinition(scheduling);
            });
        }
    }

    @Test
    void importsEveryLessonIdempotentlyAndChecksRealBm25WithoutChangingEntitlements() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/learnhub(?:\\?.*)?");
        }
        new CatalogMaterialCorpusTest().everyCatalogLessonHasUniqueSubstantialGeneratedMaterialAndLosslessChunks();
        List<JsonNode> items = CatalogMaterialCorpusTest.materials();
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(c.id,':',l.id) FROM course c JOIN course_chapter ch ON ch.course_id=c.id
                JOIN course_lesson l ON l.chapter_id=ch.id WHERE c.deleted=0 AND l.deleted=0
                """, String.class)).containsExactlyInAnyOrderElementsOf(items.stream()
                        .map(item -> item.path("courseId").asText() + ":" + item.path("lessonId").asText()).toList());
        // 保护既有资料身份、正文与版本；索引重建可更新其同步元数据。
        List<Map<String, Object>> previous = previousMaterials();
        List<Long> activeUsers = jdbc.queryForList(
                "SELECT id FROM user WHERE status='ACTIVE' AND deleted=0 ORDER BY id", Long.class);
        assertThat(activeUsers).isNotEmpty();
        int created = 0;
        int unchanged = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : items) {
            var request = CatalogMaterialCorpusTest.request(item);
            var binding = jdbc.queryForMap("""
                    SELECT c.title AS course_title, ch.title AS chapter_title, l.title AS lesson_title
                    FROM course c JOIN course_chapter ch ON ch.course_id=c.id
                    JOIN course_lesson l ON l.chapter_id=ch.id
                    WHERE c.id=? AND l.id=? AND c.deleted=0 AND l.deleted=0
                    """, request.courseId(), request.lessonId());
            assertThat(binding.get("course_title")).isEqualTo(item.path("courseTitle").asText());
            assertThat(binding.get("chapter_title")).isEqualTo(item.path("chapterTitle").asText());
            assertThat(binding.get("lesson_title")).isEqualTo(item.path("lessonTitle").asText());
            var first = store.importDocument(request);
            var repeat = store.importDocument(request);
            assertThat(repeat.unchanged()).isTrue();
            assertThat(repeat.documentId()).isEqualTo(first.documentId());
            assertThat(repeat.sourceVersion()).isEqualTo(first.sourceVersion());
            if (first.unchanged()) unchanged++; else created++;
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            sync.syncDue(100);
            int pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM knowledge_document
                    WHERE source_key LIKE 'generated.course.%' AND deleted=0
                      AND (indexed_version IS NULL OR indexed_version<>source_version OR index_status<>'INDEXED')
                    """, Integer.class);
            if (pending == 0) break;
            Thread.sleep(1000);
        }
        int authorized = 0;
        for (JsonNode item : items) {
            var request = CatalogMaterialCorpusTest.request(item);
            long document = jdbc.queryForObject("""
                    SELECT id FROM knowledge_document WHERE source_key=? AND deleted=0
                      AND indexed_version=source_version AND index_status='INDEXED'
                    """, Long.class, request.sourceKey());
            String question = item.path("smokeQuestion").asText();
            assertThat(search.search(request.courseId(), List.of(request.lessonId()), question, 100))
                    .as("课时独立索引查询 %s", request.sourceKey())
                    .anyMatch(hit -> hit.documentId() == document);
            Long reader = null;
            for (long user : activeUsers) {
                if (courses.readableLessonIds(user, request.courseId()).contains(request.lessonId())) {
                    reader = user;
                    break;
                }
            }
            if (reader != null) {
                var answer = retrieval.retrieve(reader, request.courseId(), question, 5);
                assertThat(answer.sources()).as("授权查询 %s", request.sourceKey())
                        .anyMatch(source -> source.documentId().equals(Long.toString(document)));
                authorized++;
            } else {
                // 未购买课时保持锁定，不为通过验收而新增订单或修改 free_preview。
                assertThat(retrieval.retrieve(activeUsers.get(0), request.courseId(), question, 5).sources())
                        .noneMatch(source -> source.documentId().equals(Long.toString(document)));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceKey", request.sourceKey());
            result.put("courseTitle", item.path("courseTitle").asText());
            result.put("lessonTitle", item.path("lessonTitle").asText());
            result.put("question", question);
            result.put("indexed", true);
            result.put("hasExistingAuthorizedReader", reader != null);
            results.add(result);
        }
        assertThat(previousMaterials()).isEqualTo(previous);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documents", items.size()); report.put("createdOrUpdated", created);
        report.put("unchanged", unchanged); report.put("indexQueriesPassed", results.size());
        report.put("authorizedQueriesPassed", authorized);
        report.put("lockedLessonsConfirmed", items.size() - authorized);
        report.put("previousMaterialRowsPreserved", previous.size());
        report.put("supplierCalls", 0); report.put("results", results);
        Files.createDirectories(CatalogMaterialCorpusTest.ROOT.resolve(".local"));
        Files.writeString(CatalogMaterialCorpusTest.ROOT.resolve(".local/catalog-material-import-result.json"),
                CatalogMaterialCorpusTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private List<Map<String, Object>> previousMaterials() {
        return jdbc.queryForList("""
                SELECT d.id,d.source_key,d.title,d.course_id,d.lesson_id,d.visibility,d.content_hash,
                       d.source_version,d.deleted,k.id AS chunk_id,k.source_version AS chunk_version,
                       k.chunk_order,k.heading,k.content
                FROM knowledge_document d LEFT JOIN knowledge_chunk k ON k.document_id=d.id
                WHERE d.source_key NOT LIKE 'generated.course.%' ORDER BY d.id,k.id
                """);
    }
}
