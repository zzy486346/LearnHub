package com.learnhub.assistant.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 将完整只读工具结果投影为确定性、有界且可分页的模型观察。 */
public final class ToolObservationProjection {
    private static final Set<String> SOURCE_TOOLS = Set.of(
            "search_courses", "get_course_outline", "search_course_materials", "get_my_learning_progress");
    private static final Set<String> READ_FIELDS = Set.of("SUMMARY", "DETAILS");
    private static final Set<String> READ_ARGUMENTS = Set.of("resultRef", "page", "fields");
    private static final Set<String> FULL_FIELDS = Set.of("toolCallId", "tool", "observations");
    private static final Set<String> OBSERVATION_FIELDS = Set.of("evidenceId", "kind", "data");
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_PAGE_ROWS = 8;
    private static final int PROTOCOL_RESERVE_BYTES = 1024;
    private static final int SUMMARY_TEXT_CODE_POINTS = 160;
    private static final int DETAIL_TEXT_CODE_POINTS = 400;

    private ToolObservationProjection() { }

    public static Map<String, Object> definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("resultRef", Map.of("type", "string", "description", "服务端返回的小写UUID结果引用"));
        properties.put("page", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                "default", 1, "description", "详情页码，省略时为1"));
        properties.put("fields", Map.of("type", "string", "enum", List.of("SUMMARY", "DETAILS"),
                "default", "DETAILS", "description", "读取摘要或完整详情，省略时为DETAILS"));
        return Map.of("type", "function", "function", Map.of(
                "name", "read_tool_result", "description", "按不透明引用分页回读当前运行的只读工具结果",
                "parameters", Map.of("type", "object", "properties", properties,
                        "required", List.of("resultRef"), "additionalProperties", false)));
    }

    public static ReadRequest parseRead(ObjectMapper mapper, ToolCall call) {
        if (mapper == null || call == null || !"read_tool_result".equals(call.name())) throw invalid();
        try {
            JsonNode root = strict(mapper, call.arguments());
            fields(root, READ_ARGUMENTS, Set.of("resultRef"));
            String reference = text(root.get("resultRef"));
            UUID uuid = UUID.fromString(reference);
            if (!uuid.toString().equals(reference)) throw invalid();
            int page = root.has("page") ? integer(root.get("page"), 1, 1000) : 1;
            String selected = root.has("fields") ? text(root.get("fields")) : "DETAILS";
            if (!READ_FIELDS.contains(selected)) throw invalid();
            return new ReadRequest(reference, page, selected);
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    public static String normalizedArgumentsHash(ObjectMapper mapper, ToolCall call) {
        ReadRequest request = parseRead(mapper, call);
        try {
            String normalized = mapper.writeValueAsString(new TreeMap<>(Map.of(
                    "fields", request.fields(), "page", request.page(), "resultRef", request.resultRef())));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    public static Map<String, Object> initial(ObjectMapper mapper, ToolCall original, String resultRef,
                                               Map<String, Object> full, int maxBytes) {
        if (mapper == null || original == null || !SOURCE_TOOLS.contains(original.name()) || full == null)
            throw invalid();
        requireReference(resultRef);
        ObjectNode source = object(mapper, mapper.valueToTree(full));
        validateFull(source, original.name());
        Projection projection = project(mapper, source, "SUMMARY");
        return page(mapper, original.id(), original.name(), original.name(), resultRef,
                "SUMMARY", 1, projection, maxBytes);
    }

    public static Map<String, Object> readPage(ObjectMapper mapper, ToolCall read, String sourceTool,
                                                String resultRef, String fullJson, int maxBytes) {
        ReadRequest request = parseRead(mapper, read);
        if (!request.resultRef().equals(resultRef) || !SOURCE_TOOLS.contains(sourceTool)) throw invalid();
        try {
            ObjectNode source = object(mapper, strict(mapper, fullJson));
            validateFull(source, sourceTool);
            Projection projection = project(mapper, source, request.fields());
            return page(mapper, read.id(), "read_tool_result", sourceTool, resultRef,
                    request.fields(), request.page(), projection, maxBytes);
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static Projection project(ObjectMapper mapper, ObjectNode full, String selected) {
        List<Row> rows = new ArrayList<>();
        boolean truncated = false;
        for (JsonNode observation : full.withArray("observations")) {
            String evidenceId = text(observation.get("evidenceId"));
            String kind = text(observation.get("kind"));
            ObjectNode data = object(mapper, observation.get("data")).deepCopy();
            switch (kind) {
                case "COURSE" -> rows.add(new Row(kind, courseRow(evidenceId, data), sortId(data.path("courseId").asText())));
                case "OUTLINE" -> rows.addAll(outlineRows(evidenceId, data));
                case "PROGRESS" -> rows.addAll(progressRows(evidenceId, data));
                case "MATERIAL" -> {
                    MaterialRows material = materialRows(evidenceId, data, selected);
                    rows.addAll(material.rows());
                    truncated |= material.truncated();
                }
                default -> throw invalid();
            }
            if (rows.size() > MAX_ROWS) throw limit();
        }
        return new Projection(rows.stream().map(Row::value).toList(), truncated);
    }

    private static Map<String, Object> courseRow(String evidenceId, ObjectNode data) {
        ObjectNode projected = data.objectNode();
        projected.put("courseId", requiredText(data, "courseId"));
        projected.put("title", requiredTextAllowEmpty(data, "title"));
        projected.put("instructor", requiredTextAllowEmpty(data, "instructor"));
        return observation(evidenceId, "COURSE", projected);
    }

    private static List<Row> outlineRows(String evidenceId, ObjectNode data) {
        String courseId = requiredText(data, "courseId");
        JsonNode chapters = data.get("chapters");
        if (chapters == null || !chapters.isArray()) throw invalid();
        List<ObjectNode> orderedChapters = new ArrayList<>();
        chapters.forEach(chapter -> orderedChapters.add(object(null, chapter)));
        orderedChapters.sort(nodeOrder("chapterId"));
        List<Row> rows = new ArrayList<>();
        for (ObjectNode chapter : orderedChapters) {
            JsonNode lessons = chapter.get("lessons");
            if (lessons == null || !lessons.isArray()) throw invalid();
            List<ObjectNode> orderedLessons = new ArrayList<>();
            lessons.forEach(lesson -> orderedLessons.add(object(null, lesson)));
            orderedLessons.sort(nodeOrder("lessonId"));
            if (orderedLessons.isEmpty()) {
                rows.add(new Row("OUTLINE", outlineRow(evidenceId, courseId, chapter, null),
                        sortId(requiredText(chapter, "chapterId")) + ":"));
            } else {
                for (ObjectNode lesson : orderedLessons) rows.add(new Row("OUTLINE",
                        outlineRow(evidenceId, courseId, chapter, lesson),
                        sortId(requiredText(chapter, "chapterId")) + ":" + sortId(requiredText(lesson, "lessonId"))));
            }
        }
        return rows;
    }

    private static Map<String, Object> outlineRow(String evidenceId, String courseId,
                                                   ObjectNode chapter, ObjectNode lesson) {
        ObjectNode singleChapter = chapter.objectNode();
        singleChapter.put("chapterId", requiredText(chapter, "chapterId"));
        singleChapter.put("title", requiredTextAllowEmpty(chapter, "title"));
        copyNullableInteger(chapter, singleChapter, "order");
        ArrayNode lessonArray = singleChapter.putArray("lessons");
        if (lesson != null) {
            ObjectNode item = lessonArray.addObject();
            item.put("lessonId", requiredText(lesson, "lessonId"));
            item.put("title", requiredTextAllowEmpty(lesson, "title"));
            copyNullableInteger(lesson, item, "order");
            copyNullableInteger(lesson, item, "durationSeconds");
            JsonNode accessible = lesson.get("accessible");
            if (accessible == null || !accessible.isBoolean()) throw invalid();
            item.put("accessible", accessible.booleanValue());
        }
        ObjectNode projected = chapter.objectNode();
        projected.put("courseId", courseId);
        projected.putArray("chapters").add(singleChapter);
        return observation(evidenceId, "OUTLINE", projected);
    }

    private static List<Row> progressRows(String evidenceId, ObjectNode data) {
        String courseId = requiredText(data, "courseId");
        JsonNode values = data.get("progress");
        if (values == null || !values.isArray()) throw invalid();
        List<ObjectNode> ordered = new ArrayList<>();
        values.forEach(value -> ordered.add(object(null, value)));
        ordered.sort(Comparator.comparing(value -> sortId(requiredText(value, "lessonId"))));
        List<Row> rows = new ArrayList<>();
        for (ObjectNode value : ordered) {
            ObjectNode item = value.objectNode();
            item.put("lessonId", requiredText(value, "lessonId"));
            copyNullableInteger(value, item, "positionSeconds");
            JsonNode completed = value.get("completed");
            if (completed == null || !completed.isBoolean()) throw invalid();
            item.put("completed", completed.booleanValue());
            JsonNode last = value.get("lastLearnedAt");
            if (last == null || last.isNull()) item.putNull("lastLearnedAt");
            else item.put("lastLearnedAt", text(last));
            ObjectNode projected = value.objectNode();
            projected.put("courseId", courseId);
            projected.putArray("progress").add(item);
            rows.add(new Row("PROGRESS", observation(evidenceId, "PROGRESS", projected),
                    sortId(requiredText(value, "lessonId"))));
        }
        return rows;
    }

    private static MaterialRows materialRows(String evidenceId, ObjectNode data, String selected) {
        String text = requiredTextAllowEmpty(data, "text");
        String key = sortId(requiredText(data, "chunkId"));
        if ("SUMMARY".equals(selected)) {
            String excerpt = prefix(text, SUMMARY_TEXT_CODE_POINTS);
            ObjectNode projected = materialIdentity(data);
            projected.put("text", excerpt);
            Map<String, Object> row = observation(evidenceId, "MATERIAL", projected);
            boolean omitted = !excerpt.equals(text);
            row.put("textOmitted", omitted);
            return new MaterialRows(List.of(new Row("MATERIAL", row, key)), omitted);
        }
        List<String> parts = parts(text, DETAIL_TEXT_CODE_POINTS);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            ObjectNode projected = materialIdentity(data);
            projected.put("text", parts.get(index));
            Map<String, Object> row = observation(evidenceId, "MATERIAL", projected);
            row.put("partIndex", index + 1);
            row.put("partCount", parts.size());
            rows.add(new Row("MATERIAL", row, key + ":" + String.format("%05d", index + 1)));
        }
        return new MaterialRows(rows, false);
    }

    private static ObjectNode materialIdentity(ObjectNode source) {
        for (String name : List.of("documentId", "chunkId", "courseId", "lessonId", "title"))
            if (!source.has(name)) throw invalid();
        ObjectNode projected = source.deepCopy();
        projected.remove("text");
        return projected;
    }

    private static Map<String, Object> page(ObjectMapper mapper, String toolCallId, String tool,
                                             String sourceTool, String resultRef, String selected, int requestedPage,
                                             Projection projection, int maxBytes) {
        if (maxBytes < 1024 || maxBytes > 1_000_000 || toolCallId == null || toolCallId.isBlank()) throw invalid();
        List<List<Map<String, Object>>> pages = pack(mapper, projection.rows(), maxBytes);
        if (requestedPage < 1 || requestedPage > pages.size()) throw invalid();
        List<Map<String, Object>> selectedRows = pages.get(requestedPage - 1);
        boolean hasMore = requestedPage < pages.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolCallId", toolCallId);
        result.put("tool", tool);
        result.put("sourceTool", sourceTool);
        result.put("resultRef", resultRef);
        result.put("fields", selected);
        result.put("page", requestedPage);
        result.put("observations", selectedRows);
        result.put("totalCount", projection.rows().size());
        result.put("projectedCount", selectedRows.size());
        result.put("omitted", projection.truncated() || hasMore || requestedPage > 1);
        result.put("totalPages", pages.size());
        result.put("hasMore", hasMore);
        result.put("nextPage", hasMore ? requestedPage + 1 : null);
        try {
            if (mapper.writeValueAsBytes(result).length > maxBytes) throw limit();
            return result;
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static List<List<Map<String, Object>>> pack(ObjectMapper mapper, List<Map<String, Object>> rows, int maxBytes) {
        int rowBudget = maxBytes - PROTOCOL_RESERVE_BYTES;
        if (rowBudget < 1) throw limit();
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int bytes = 0;
        for (Map<String, Object> row : rows) {
            int size;
            try { size = mapper.writeValueAsBytes(row).length; }
            catch (Exception malformed) { throw invalid(); }
            if (size > rowBudget) throw limit();
            int addition = size + (current.isEmpty() ? 0 : 1);
            if (current.size() == MAX_PAGE_ROWS || bytes + addition > rowBudget) {
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
                bytes = 0;
                addition = size;
            }
            current.add(row);
            bytes += addition;
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        if (pages.isEmpty()) pages.add(List.of());
        return List.copyOf(pages);
    }

    private static void validateFull(ObjectNode root, String expectedTool) {
        fields(root, FULL_FIELDS, FULL_FIELDS);
        if (!expectedTool.equals(text(root.get("tool")))) throw invalid();
        text(root.get("toolCallId"));
        JsonNode observations = root.get("observations");
        if (!observations.isArray() || observations.size() > MAX_ROWS) throw limit();
        for (JsonNode observation : observations) {
            fields(observation, OBSERVATION_FIELDS, OBSERVATION_FIELDS);
            text(observation.get("evidenceId"));
            text(observation.get("kind"));
            object(null, observation.get("data"));
        }
    }

    private static JsonNode strict(ObjectMapper mapper, String json) throws Exception {
        if (json == null || json.isBlank()) throw invalid();
        return mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
    }

    private static void fields(JsonNode root, Set<String> allowed, Set<String> required) {
        if (root == null || !root.isObject()) throw invalid();
        root.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
        for (String name : required) if (!root.has(name)) throw invalid();
    }

    private static ObjectNode object(ObjectMapper ignored, JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        return (ObjectNode) value;
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String requiredText(ObjectNode value, String field) { return text(value.get(field)); }

    private static String requiredTextAllowEmpty(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static int integer(JsonNode value, int minimum, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int number = value.intValue();
        if (number < minimum || number > maximum) throw invalid();
        return number;
    }

    private static void copyNullableInteger(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) target.putNull(field);
        else {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            target.put(field, value.longValue());
        }
    }

    private static Comparator<ObjectNode> nodeOrder(String identity) {
        return Comparator.comparingInt((ObjectNode node) -> nullableOrder(node.get("order")))
                .thenComparing(node -> sortId(requiredText(node, identity)));
    }

    private static int nullableOrder(JsonNode value) {
        if (value == null || value.isNull()) return Integer.MAX_VALUE;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static String sortId(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        return String.format("%02d:%s", value.length(), value);
    }

    private static String prefix(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static List<String> parts(String value, int maximum) {
        if (value.isEmpty()) return List.of("");
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int count = Math.min(maximum, value.codePointCount(start, value.length()));
            int end = value.offsetByCodePoints(start, count);
            result.add(value.substring(start, end));
            start = end;
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> observation(String evidenceId, String kind, ObjectNode data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("evidenceId", evidenceId);
        row.put("kind", kind);
        row.put("data", data);
        return row;
    }

    private static void requireReference(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_TOOL_INVALID", "工具名称或参数未通过校验");
    }

    private static BusinessException limit() {
        return new BusinessException("ASSISTANT_TOOL_RESULT_LIMIT", "工具结果超出受控投影上限");
    }

    public record ReadRequest(String resultRef, int page, String fields) { }
    private record Projection(List<Map<String, Object>> rows, boolean truncated) { }
    private record MaterialRows(List<Row> rows, boolean truncated) { }
    private record Row(String kind, Map<String, Object> value, String sortKey) { }
}
