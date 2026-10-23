package com.learnhub.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolObservationProjectionTest {
    private static final String REF = "123e4567-e89b-42d3-a456-426614174000";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void definitionAndReadArgumentsAreStrictAndCanonical() {
        assertThat(ToolObservationProjection.definition().toString())
                .contains("read_tool_result", "resultRef", "SUMMARY", "DETAILS", "additionalProperties=false");
        var request = ToolObservationProjection.parseRead(mapper, new ToolCall("read", "read_tool_result",
                "{\"resultRef\":\"" + REF + "\",\"page\":2,\"fields\":\"DETAILS\"}"));
        assertThat(request).isEqualTo(new ToolObservationProjection.ReadRequest(REF, 2, "DETAILS"));
        assertThat(ToolObservationProjection.normalizedArgumentsHash(mapper,
                new ToolCall("a", "read_tool_result", "{\"resultRef\":\"" + REF + "\"}")))
                .isEqualTo(ToolObservationProjection.normalizedArgumentsHash(mapper,
                        new ToolCall("b", "read_tool_result",
                                "{\"fields\":\"DETAILS\",\"page\":1,\"resultRef\":\"" + REF + "\"}")));

        for (String arguments : List.of(
                "{\"resultRef\":\"https://example.com/x\"}",
                "{\"resultRef\":\"" + REF.toUpperCase() + "\"}",
                "{\"resultRef\":\"" + REF + "\",\"page\":0}",
                "{\"resultRef\":\"" + REF + "\",\"fields\":\"SQL\"}",
                "{\"resultRef\":\"" + REF + "\",\"path\":\"/tmp/x\"}",
                "{\"resultRef\":\"" + REF + "\",\"page\":1,\"page\":2}")) {
            assertThatThrownBy(() -> ToolObservationProjection.parseRead(mapper,
                    new ToolCall("bad", "read_tool_result", arguments)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_TOOL_INVALID"));
        }
    }

    @Test void projectsAllFourKindsDeterministicallyWithoutMutatingFullResult() throws Exception {
        Map<String, Object> full = full("original", "search_course_materials", List.of(
                observation("E4", "PROGRESS", Map.of("courseId", "10", "progress", List.of(
                        Map.of("lessonId", "102", "positionSeconds", 20, "completed", false, "lastLearnedAt", "2026-10-21T10:00"),
                        Map.of("lessonId", "101", "positionSeconds", 30, "completed", true, "lastLearnedAt", "2026-10-20T10:00")))),
                observation("E2", "OUTLINE", Map.of("courseId", "10", "chapters", List.of(
                        Map.of("chapterId", "2", "title", "第二章", "order", 1, "lessons", List.of()),
                        Map.of("chapterId", "1", "title", "第一章", "order", 2, "lessons", List.of(
                                Map.of("lessonId", "11", "title", "后课", "order", 2, "durationSeconds", 20, "accessible", false),
                                Map.of("lessonId", "12", "title", "先课", "order", 1, "durationSeconds", 10, "accessible", true)))))),
                observation("E3", "MATERIAL", material("12", "短正文")),
                observation("E1", "COURSE", Map.of("courseId", "20", "title", "相关度第一", "instructor", "老师")),
                observation("E5", "COURSE", Map.of("courseId", "1", "title", "相关度第二", "instructor", "老师"))));
        String before = mapper.writeValueAsString(full);

        Map<String, Object> first = ToolObservationProjection.initial(mapper,
                new ToolCall("original", "search_course_materials", "{}"), REF, full, 8192);
        Map<String, Object> again = ToolObservationProjection.initial(mapper,
                new ToolCall("original", "search_course_materials", "{}"), REF, full, 8192);

        assertThat(first).isEqualTo(again);
        assertThat(mapper.writeValueAsString(full)).isEqualTo(before);
        assertThat(first).containsEntry("fields", "SUMMARY").containsEntry("sourceTool", "search_course_materials");
        List<Map<String, Object>> rows = rows(first);
        assertThat(rows).extracting(row -> row.get("kind"))
                .containsExactly("PROGRESS", "PROGRESS", "OUTLINE", "OUTLINE", "OUTLINE", "MATERIAL", "COURSE", "COURSE");
        assertThat(mapper.writeValueAsString(rows.get(2))).contains("第二章");
        assertThat(mapper.writeValueAsString(rows.get(3))).contains("第一章", "先课", "\"lessonId\":\"12\"");
        assertThat(mapper.writeValueAsString(rows.get(4))).contains("第一章", "后课", "\"lessonId\":\"11\"");
        assertThat(data(rows.get(6)).path("title").asText()).isEqualTo("相关度第一");
        assertThat(data(rows.get(7)).path("title").asText()).isEqualTo("相关度第二");
    }

    @Test void detailsUnicodeChunksAndPaginationHaveNoDuplicateOrMissingRows() throws Exception {
        String text = "缓存🙂一致性".repeat(190) + "TAIL_MARKER";
        List<Map<String, Object>> observations = new ArrayList<>();
        for (int i = 12; i >= 1; i--) observations.add(observation("E" + i, "MATERIAL",
                material(Integer.toString(i), i == 12 ? text : "正文" + i)));
        Map<String, Object> full = full("source-call", "search_course_materials", observations);
        String fullJson = mapper.writeValueAsString(full);
        ToolCall firstRead = new ToolCall("read-1", "read_tool_result",
                "{\"resultRef\":\"" + REF + "\",\"page\":1,\"fields\":\"DETAILS\"}");
        Map<String, Object> page1 = ToolObservationProjection.readPage(mapper, firstRead,
                "search_course_materials", REF, fullJson, 4096);
        int pages = (Integer) page1.get("totalPages");
        List<String> chunks = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            Map<String, Object> result = ToolObservationProjection.readPage(mapper,
                    new ToolCall("read-" + page, "read_tool_result", "{\"resultRef\":\"" + REF
                            + "\",\"page\":" + page + ",\"fields\":\"DETAILS\"}"),
                    "search_course_materials", REF, fullJson, 4096);
            assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(4096);
            assertThat(result.get("page")).isEqualTo(page);
            assertThat(result.get("hasMore")).isEqualTo(page < pages);
            for (Map<String, Object> row : rows(result)) {
                JsonNode data = data(row);
                identities.add(data.path("chunkId").asText() + ":" + row.get("partIndex"));
                if ("12".equals(data.path("chunkId").asText())) chunks.add(data.path("text").asText());
            }
        }
        assertThat(identities).doesNotHaveDuplicates();
        assertThat(String.join("", chunks)).isEqualTo(text).endsWith("TAIL_MARKER");
        assertThat(text.codePoints().count()).isEqualTo(chunks.stream().mapToLong(value -> value.codePoints().count()).sum());
        assertThat(page1).containsEntry("omitted", true);
    }

    @Test void pagePackingUsesActualPreviousBoundaryAndRejectsRowsOrMetadataThatCannotFit() throws Exception {
        List<Map<String, Object>> observations = new ArrayList<>();
        for (int i = 1; i <= 11; i++) observations.add(observation("E" + i, "COURSE",
                Map.of("courseId", Integer.toString(i), "title", "标题".repeat(i * 10), "instructor", "讲师")));
        String json = mapper.writeValueAsString(full("source-call", "search_courses", observations));
        List<String> seen = new ArrayList<>();
        int page = 1;
        int totalPages;
        do {
            Map<String, Object> result = ToolObservationProjection.readPage(mapper,
                    new ToolCall("read-" + page, "read_tool_result", "{\"resultRef\":\"" + REF
                            + "\",\"page\":" + page + ",\"fields\":\"DETAILS\"}"),
                    "search_courses", REF, json, 2048);
            rows(result).forEach(row -> seen.add(data(row).path("courseId").asText()));
            totalPages = (Integer) result.get("totalPages");
            page++;
        } while (page <= totalPages);
        assertThat(seen).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");

        Map<String, Object> huge = full("source", "search_courses", List.of(observation("E1", "COURSE",
                Map.of("courseId", "1", "title", "大".repeat(2000), "instructor", "讲师"))));
        assertThatThrownBy(() -> ToolObservationProjection.initial(mapper,
                new ToolCall("source", "search_courses", "{}"), REF, huge, 2048))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_TOOL_RESULT_LIMIT"));
    }

    private static Map<String, Object> full(String call, String tool, List<Map<String, Object>> observations) {
        return new LinkedHashMap<>(Map.of("toolCallId", call, "tool", tool, "observations", observations));
    }

    private static Map<String, Object> observation(String id, String kind, Map<String, Object> data) {
        return new LinkedHashMap<>(Map.of("evidenceId", id, "kind", kind, "data", data));
    }

    private static Map<String, Object> material(String chunk, String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", "9"); result.put("sourceVersion", 1); result.put("chunkId", chunk);
        result.put("courseId", "10"); result.put("lessonId", "101"); result.put("title", "Redis");
        result.put("heading", "Lua"); result.put("chunkOrder", 0); result.put("text", text); result.put("score", 1.0);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("observations");
    }

    private JsonNode data(Map<String, Object> row) {
        return mapper.valueToTree(row.get("data"));
    }
}
