package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeSearchGatewayTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void bm25FiltersCourseAndReadableLessonsAndDoesNotReturnEsBody() {
        var query = json.valueToTree(KnowledgeSearchGateway.query(1L, List.of(20L, 21L), "Lua 原子性", 10));
        assertThat(query.path("query").path("bool").path("filter").get(0).path("term").path("courseId").asLong()).isEqualTo(1);
        assertThat(query.path("query").path("bool").path("filter").get(1).path("terms").path("lessonId").size()).isEqualTo(2);
        assertThat(query.path("_source").toString()).doesNotContain("content", "title", "heading");
        assertThat(query.path("query").path("bool").path("must").toString()).contains("multi_match", "Lua 原子性");
    }

    @Test void boundsDoNotAllowUnlimitedSearchOrHugeScope() {
        assertThatThrownBy(() -> KnowledgeSearchGateway.query(1, List.of(1L), "x", 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeSearchGateway.query(1, List.of(1L), "x".repeat(501), 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
