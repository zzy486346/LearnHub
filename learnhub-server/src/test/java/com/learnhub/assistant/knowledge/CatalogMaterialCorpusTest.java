package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 固定目录的完整性检查，不连接数据库或模型供应商。 */
class CatalogMaterialCorpusTest {
    static final Path ROOT = repositoryRoot();
    static final ObjectMapper JSON = new ObjectMapper();

    static List<JsonNode> materials() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String part : List.of("a", "b", "c")) {
            JsonNode items = JSON.readTree(Files.readString(ROOT.resolve(
                    "docs/assistant-materials/lectures-" + part + ".json")));
            assertThat(items.isArray()).isTrue();
            items.forEach(result::add);
        }
        return result;
    }

    static ImportDocumentRequest request(JsonNode item) {
        List<ImportDocumentRequest.Scene> scenes = new ArrayList<>();
        item.withArray("scenes").forEach(scene -> scenes.add(new ImportDocumentRequest.Scene(
                scene.path("heading").asText(), scene.path("content").asText())));
        return new ImportDocumentRequest(item.path("sourceKey").asText(), item.path("title").asText(),
                Long.parseLong(item.path("courseId").asText()), Long.parseLong(item.path("lessonId").asText()), scenes);
    }

    @Test
    void everyCatalogLessonHasUniqueSubstantialGeneratedMaterialAndLosslessChunks() throws Exception {
        JsonNode catalog = JSON.readTree(Files.readString(ROOT.resolve("docs/assistant-materials/catalog-20261020.json")));
        List<JsonNode> items = materials();
        assertThat(items).hasSize(catalog.size()).hasSize(51);
        Set<String> keys = new HashSet<>();
        Set<String> lessons = new HashSet<>();
        Set<String> courses = new HashSet<>();
        Set<String> bodies = new HashSet<>();
        for (JsonNode item : items) {
            var request = request(item);
            JsonNode binding = null;
            for (JsonNode row : catalog) {
                if (row.path("lessonId").asText().equals(item.path("lessonId").asText())) binding = row;
            }
            assertThat(binding).isNotNull();
            for (String field : List.of("courseId", "courseTitle", "chapterTitle", "lessonId", "lessonTitle"))
                assertThat(item.path(field).asText()).as(field).isEqualTo(binding.path(field).asText());
            assertThat(request.sourceKey()).isEqualTo("generated.course." + request.courseId() + ".lesson." + request.lessonId());
            assertThat(keys.add(request.sourceKey())).isTrue();
            assertThat(lessons.add(item.path("lessonId").asText())).isTrue();
            courses.add(item.path("courseId").asText());
            assertThat(request.scenes()).hasSize(5);
            assertThat(item.path("smokeQuestion").asText()).isNotBlank().hasSizeLessThanOrEqualTo(500);
            String body = request.scenes().stream().map(ImportDocumentRequest.Scene::content)
                    .reduce("", (a, b) -> a + b);
            assertThat(body.length()).isBetween(700, 100_000);
            assertThat(body).contains("生成", "非原授课逐字稿");
            assertThat(bodies.add(body)).isTrue();
            var chunks = new KnowledgeChunker().split(request.scenes());
            assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
                assertThat(chunk.content()).isNotBlank();
                assertThat(chunk.content().length()).isLessThanOrEqualTo(600);
            });
            for (var scene : request.scenes()) {
                assertThat(scene.heading()).isNotBlank();
                assertThat(scene.content()).isNotBlank();
            }
        }
        assertThat(courses).hasSize(24);
    }

    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("docs/assistant-materials/catalog-20261020.json"))) return path;
        }
        throw new IllegalStateException("无法定位固定课程目录");
    }
}
