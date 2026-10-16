package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "learnhub.assistant.stage-a.import", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false",
        "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false",
        "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false",
        "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false"
})
class StageAMaterialImportIntegrationTest {
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeStore store;
    @Autowired private KnowledgeIndexSyncService indexSync;
    @Autowired private KnowledgeRetrievalService retrieval;

    @Test
    void importsReviewedMaterialsAndRunsDevelopmentRetrievalSet() throws Exception {
        JsonNode catalog = readJson("scripts/course-media/catalog.json");
        JsonNode mapping = readJson("scripts/assistant/stage-a-mapping.json");
        assertThat(mapping.path("reviewed").asBoolean()).isTrue();

        Map<String, CatalogLesson> lessons = catalogLessons(catalog);
        List<ImportDocumentRequest> requests = new ArrayList<>();
        for (JsonNode item : mapping.withArray("mappings")) {
            CatalogLesson lesson = lessons.get(key(item.path("courseSlug").asText(), item.path("lessonTitle").asText()));
            assertThat(lesson).as("catalog 映射必须唯一存在").isNotNull();
            long courseId = parsePositiveLong(item, "courseId");
            long lessonId = parsePositiveLong(item, "lessonId");
            assertDatabaseBinding(courseId, lessonId, lesson);
            requests.add(new ImportDocumentRequest(item.path("sourceKey").asText(), lesson.lessonTitle(),
                    courseId, lessonId, lesson.scenes()));
        }
        assertThat(requests).hasSize(6);
        assertThat(requests).flatExtracting(ImportDocumentRequest::scenes).hasSize(18);

        for (ImportDocumentRequest request : requests) {
            ImportDocumentResult first = store.importDocument(request);
            ImportDocumentResult repeated = store.importDocument(request);
            assertThat(first.chunkCount()).isEqualTo(request.scenes().size());
            assertThat(repeated.documentId()).isEqualTo(first.documentId());
            assertThat(repeated.sourceVersion()).isEqualTo(first.sourceVersion());
            assertThat(repeated.unchanged()).isTrue();
        }

        int synced = indexSync.syncDue(20);
        assertThat(synced).isBetween(0, requests.size());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document
                WHERE source_key LIKE 'catalog.%' AND deleted = 0 AND indexed_version = source_version
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk kc JOIN knowledge_document kd ON kd.id = kc.document_id
                WHERE kd.source_key LIKE 'catalog.%' AND kd.deleted = 0 AND kc.source_version = kd.source_version
                """, Integer.class)).isEqualTo(18);

        long userId = activeAdminUserId();
        JsonNode development = readJson("docs/assistant-retrieval-development.json");
        int executed = 0;
        for (JsonNode testCase : development.withArray("cases")) {
            var result = retrieval.retrieve(userId, testCase.path("courseId").asLong(),
                    testCase.path("query").asText(), 5);
            List<String> actualSources = result.sources().stream().map(source -> jdbc.queryForObject(
                    "SELECT source_key FROM knowledge_document WHERE id = ?", String.class,
                    Long.parseLong(source.documentId()))).distinct().toList();
            List<String> expected = strings(testCase.withArray("expectedSourceKeys"));
            List<String> forbidden = strings(testCase.withArray("forbiddenSourceKeys"));
            String category = testCase.path("category").asText();
            if ("ANSWERABLE".equals(category)) {
                assertThat(actualSources).as(testCase.path("id").asText()).containsAnyElementsOf(expected);
                String evidence = result.sources().stream().map(KnowledgeRetrievalService.Source::text)
                        .reduce("", (left, right) -> left + "\n" + right);
                assertThat(evidence).as(testCase.path("id").asText())
                        .contains(strings(testCase.withArray("expectedTerms")).toArray(String[]::new));
            } else if ("CROSS_COURSE_ISOLATION".equals(category)) {
                assertThat(actualSources).as(testCase.path("id").asText()).doesNotContainAnyElementsOf(forbidden);
            }
            System.out.printf("STAGE_A_DEV case=%s category=%s sourceKeys=%s noRelevant=%s%n",
                    testCase.path("id").asText(), category, actualSources, result.noRelevantMaterials());
            executed++;
        }
        assertThat(executed).isEqualTo(8);
    }

    private void assertDatabaseBinding(long courseId, long lessonId, CatalogLesson expected) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.title AS course_title, cc.title AS chapter_title, cl.title AS lesson_title,
                       c.status AS course_status, c.deleted AS course_deleted, cl.deleted AS lesson_deleted
                FROM course c
                JOIN course_chapter cc ON cc.course_id = c.id
                JOIN course_lesson cl ON cl.chapter_id = cc.id
                WHERE c.id = ? AND cl.id = ?
                """, courseId, lessonId);
        assertThat(rows).as("课程课时归属必须唯一：%s/%s", courseId, lessonId).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("course_title")).isEqualTo(expected.courseTitle());
        assertThat(row.get("chapter_title")).isEqualTo(expected.chapterTitle());
        assertThat(row.get("lesson_title")).isEqualTo(expected.lessonTitle());
        assertThat(row.get("course_status")).isEqualTo("PUBLISHED");
        assertThat(((Number) row.get("course_deleted")).intValue()).isZero();
        assertThat(((Number) row.get("lesson_deleted")).intValue()).isZero();
    }

    private long activeAdminUserId() {
        List<Long> ids = jdbc.queryForList("""
                SELECT DISTINCT u.id FROM user u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.status = 'ACTIVE' AND u.deleted = 0 AND ur.role_code = 'ADMIN'
                ORDER BY u.id
                """, Long.class);
        assertThat(ids).as("真实数据库必须已有 ACTIVE ADMIN 用户，仅使用其身份做免费预览检索").isNotEmpty();
        return ids.get(0);
    }

    private Map<String, CatalogLesson> catalogLessons(JsonNode catalog) {
        Map<String, CatalogLesson> result = new LinkedHashMap<>();
        for (JsonNode course : catalog) {
            for (JsonNode lesson : course.withArray("lessons")) {
                List<ImportDocumentRequest.Scene> scenes = new ArrayList<>();
                for (JsonNode scene : lesson.withArray("scenes")) {
                    String narration = scene.path("narration").asText().trim();
                    String content = narration.isEmpty() ? scene.path("body").asText().trim() : narration;
                    scenes.add(new ImportDocumentRequest.Scene(scene.path("heading").asText().trim(), content));
                }
                var value = new CatalogLesson(course.path("title").asText(), lesson.path("chapterTitle").asText(),
                        lesson.path("title").asText(), List.copyOf(scenes));
                assertThat(result.put(key(course.path("slug").asText(), value.lessonTitle()), value)).isNull();
            }
        }
        return result;
    }

    private JsonNode readJson(String relativePath) throws Exception {
        return objectMapper.readTree(Files.readString(REPOSITORY_ROOT.resolve(relativePath)));
    }

    private static long parsePositiveLong(JsonNode item, String field) {
        long value = Long.parseLong(item.path(field).asText());
        assertThat(value).isPositive();
        return value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static String key(String courseSlug, String lessonTitle) {
        return courseSlug + "\u0000" + lessonTitle;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts/assistant/stage-a-mapping.json"))) return candidate;
        }
        throw new IllegalStateException("无法定位 LearnHub 仓库根目录");
    }

    private record CatalogLesson(String courseTitle, String chapterTitle, String lessonTitle,
                                 List<ImportDocumentRequest.Scene> scenes) {}
}
