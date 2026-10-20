package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class LocalHybridBackfillSafetyTest {
    @Test
    void refusesMemoryDatabaseRemoteHostAndWrongPortBeforeRefresh() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:15432/learnhub_memory",
                "jdbc:postgresql://example.invalid:15432/learnhub_vector",
                "jdbc:postgresql://127.0.0.1:5432/learnhub_vector")) {
            try (var context = new GenericApplicationContext()) {
                Map<String, Object> settings = new HashMap<>(Map.of(
                        "spring.datasource.url", "jdbc:mysql://localhost:3306/learnhub",
                        "spring.elasticsearch.uris", "http://localhost:9200",
                        "spring.data.redis.host", "localhost", "LEARNHUB_VECTOR_JDBC_URL", url));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("localGuard", settings));
                assertThatThrownBy(() -> new LocalHybridBackfillIntegrationTest.LocalOnly().initialize(context))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("回填仅允许本机");
                assertThat(context.isActive()).isFalse();
            }
        }
    }

    @Test
    void frozenQuestionsCoverAllCoursesAndOnlyReferenceActualSources() throws Exception {
        JsonNode dataset = CatalogMaterialCorpusTest.JSON.readTree(Files.readString(
                CatalogMaterialCorpusTest.ROOT.resolve("docs/assistant-retrieval-local-20261020.json")));
        JsonNode cases = dataset.path("cases");
        assertThat(cases.size()).isEqualTo(72);
        assertThat(cases.findValuesAsText("id")).doesNotHaveDuplicates();
        List<JsonNode> materials = CatalogMaterialCorpusTest.materials();
        Map<String, String> sourceCourses = materials.stream().collect(Collectors.toMap(
                item -> item.path("sourceKey").asText(), item -> item.path("courseId").asText()));
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> coverage = new HashMap<>();
        for (JsonNode item : cases) {
            String category = item.path("category").asText();
            String course = item.path("courseId").asText();
            assertThat(Set.of("ANSWERABLE", "NO_EVIDENCE", "CROSS_COURSE")).contains(category);
            assertThat(sourceCourses).containsValue(course);
            assertThat(item.path("query").asText()).isNotBlank().hasSizeLessThanOrEqualTo(500);
            counts.merge(category, 1, Integer::sum);
            if ("ANSWERABLE".equals(category)) {
                coverage.merge(course, 1, Integer::sum);
                assertThat(item.path("expectedSourceKeys").size()).isPositive();
                for (JsonNode key : item.path("expectedSourceKeys"))
                    assertThat(sourceCourses.get(key.asText())).isEqualTo(course);
            } else assertThat(item.path("expectedSourceKeys").size()).isZero();
            if ("CROSS_COURSE".equals(category)) {
                assertThat(item.path("forbiddenCourseIds").size()).isPositive();
                for (JsonNode forbidden : item.path("forbiddenCourseIds"))
                    assertThat(forbidden.asText()).isNotEqualTo(course);
            }
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("ANSWERABLE", 48, "NO_EVIDENCE", 12, "CROSS_COURSE", 12));
        assertThat(coverage).hasSize(24).allSatisfy((course, count) -> assertThat(count).isEqualTo(2));
    }
}
