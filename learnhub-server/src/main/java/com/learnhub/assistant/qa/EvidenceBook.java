package com.learnhub.assistant.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.memory.PreferenceSnapshot;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 本次运行独有的证据账本，不保存会话正文。 */
public final class EvidenceBook {
    private final Long userId;
    private final long courseId;
    private final KnowledgeStore store;
    private final CourseService courses;
    private final ObjectMapper mapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, KnowledgeRetrievalService.Source> materials = new LinkedHashMap<>();
    private final Map<String, PreferenceEntry> preferences = new LinkedHashMap<>();
    private boolean preferenceInvalidated;

    public EvidenceBook(Long userId, long courseId, KnowledgeStore store, CourseService courses, ObjectMapper mapper) {
        this.userId = userId; this.courseId = courseId; this.store = store; this.courses = courses; this.mapper = mapper;
    }

    public String material(KnowledgeRetrievalService.Source source) {
        if (!Long.toString(courseId).equals(source.courseId())) throw changed();
        String key = "material:" + source.chunkId();
        String id = add(key, "MATERIAL", materialData(source), null);
        var previous = materials.putIfAbsent(key, source);
        if (previous != null && !materialData(previous).equals(materialData(source))) throw changed();
        return id;
    }

    public String register(String key, String kind, JsonNode data, Supplier<JsonNode> current) {
        if (!Set.of("OUTLINE", "PROGRESS", "COURSE").contains(kind) || current == null)
            throw new IllegalArgumentException("结构化证据必须提供当前快照复核器");
        return add(key, kind, data, current);
    }

    /** 偏好是可撤销的个性化依赖，不能领取课程事实的 E# 引用编号。 */
    public void registerPreference(PreferenceSnapshot snapshot, Supplier<PreferenceSnapshot> current) {
        if (!validPreference(snapshot) || current == null || preferenceInvalidated) throw changed();
        var previous = preferences.get(snapshot.id());
        if (previous != null) {
            if (!previous.snapshot().equals(snapshot)) throw changed();
            return;
        }
        if (preferences.size() >= 10)
            throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "学习偏好数量已达到本次上限");
        preferences.put(snapshot.id(), new PreferenceEntry(snapshot, current));
    }

    public List<PreferenceSnapshot> preferenceContext() {
        return preferences.values().stream().map(PreferenceEntry::snapshot).toList();
    }

    public List<PreferenceSnapshot> exportPreferenceDependencies() { return preferenceContext(); }

    private static boolean validPreference(PreferenceSnapshot value) {
        if (value == null || value.id() == null || value.version() <= 0 || value.category() == null
                || !Set.of("STYLE", "PACE", "LEVEL", "GOAL").contains(value.category())
                || value.text() == null || value.text().isBlank() || value.text().length() > 200) return false;
        try {
            if (!UUID.fromString(value.id()).toString().equals(value.id())) return false;
        } catch (IllegalArgumentException malformed) { return false; }
        for (int i = 0; i < value.text().length(); i++) {
            char current = value.text().charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.text().length() || !Character.isLowSurrogate(value.text().charAt(i))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }
    private String add(String key, String kind, JsonNode data, Supplier<JsonNode> current) {
        var previous = entries.get(key);
        if (previous != null) {
            if (!previous.data().equals(data) || !previous.kind().equals(kind)) throw changed();
            return previous.id();
        }
        if (entries.size() >= 20) throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "证据数量已达到本次上限");
        String id = "E" + (entries.size() + 1);
        entries.put(key, new Entry(id, kind, data.deepCopy(), current));
        return id;
    }

    /** 每次发送历史以及最终返回前调用，材料一次批量回到 MySQL 复核。 */
    public void check() {
        if (preferenceInvalidated) throw changed();
        courses.requireAvailable(courseId, userId);
        if (!materials.isEmpty()) {
            Set<Long> readable = new HashSet<>(courses.readableLessonIds(userId, courseId));
            var current = store.currentChunks(materials.values().stream().map(s -> Long.parseLong(s.chunkId())).toList())
                    .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, chunk -> chunk));
            for (var source : materials.values()) {
                var chunk = current.get(Long.parseLong(source.chunkId()));
                if (chunk == null || chunk.courseId() != courseId || !readable.contains(chunk.lessonId())
                        || chunk.lessonId() != Long.parseLong(source.lessonId())
                        || chunk.documentId() != Long.parseLong(source.documentId())
                        || chunk.sourceVersion() != source.sourceVersion()
                        || !Objects.equals(chunk.content(), source.text()) || !Objects.equals(chunk.title(), source.title())
                        || !Objects.equals(chunk.heading(), source.heading()) || chunk.chunkOrder() != source.chunkOrder()) throw changed();
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.current() != null && !entry.data().equals(entry.current().get())) throw changed();
        }
        for (PreferenceEntry preference : preferences.values()) {
            try {
                if (!preference.snapshot().equals(preference.current().get())) {
                    preferenceInvalidated = true;
                    throw changed();
                }
            } catch (RuntimeException unavailableOrChanged) {
                preferenceInvalidated = true;
                throw changed();
            }
        }
    }

    public List<Map<String, Object>> context() {
        return entries.values().stream().map(this::observation).toList();
    }

    /** 只导出有序依赖数据；复核器必须在下一次运行按当前服务端身份重新构造。 */
    public List<Dependency> exportDependencies() {
        return entries.entrySet().stream().map(item -> new Dependency(item.getKey(), item.getValue().kind(),
                materials.get(item.getKey()), "MATERIAL".equals(item.getValue().kind())
                ? null : item.getValue().data().deepCopy())).toList();
    }
    public List<Map<String, Object>> references() {
        return entries.values().stream().map(entry -> {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("evidenceId", entry.id()); reference.put("kind", entry.kind());
            for (String key : List.of("documentId", "sourceVersion", "chunkId", "courseId", "lessonId"))
                if (entry.data().hasNonNull(key)) reference.put(key, entry.data().get(key).asText());
            return reference;
        }).toList();
    }

    public Map<String, Object> observation(String id) { return observation(require(id)); }
    private Map<String, Object> observation(Entry entry) {
        return Map.of("evidenceId", entry.id(), "kind", entry.kind(), "data", entry.data().deepCopy());
    }
    public boolean contains(String id) { return entries.values().stream().anyMatch(e -> e.id().equals(id)); }
    public String kind(String id) { return require(id).kind(); }

    public Citation citation(String id) {
        var entry = require(id); var data = entry.data();
        return new Citation(id, entry.kind(), data.path("courseId").asText(null), data.path("lessonId").asText(null),
                data.path("documentId").asText(null), data.path("sourceVersion").asLong(0),
                data.path("chunkId").asText(null), data.path("title").asText("课程学习记录"),
                data.path("heading").asText(""), excerpt(data.path("text").asText("")),
                "MATERIAL".equals(entry.kind()) ? null : data.deepCopy());
    }

    private Entry require(String id) {
        return entries.values().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("ASSISTANT_OUTPUT_INVALID", "模型引用了本次证据集合以外的来源"));
    }
    private JsonNode materialData(KnowledgeRetrievalService.Source source) {
        var node = mapper.valueToTree(source).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("score");
        return node;
    }
    private static String excerpt(String text) {
        int end = text.offsetByCodePoints(0, Math.min(160, text.codePointCount(0, text.length())));
        return text.substring(0, end);
    }
    private static BusinessException changed() {
        return new BusinessException("ASSISTANT_EVIDENCE_CHANGED", "课程权限或资料版本已变化，本次回答已废弃，请重新提问");
    }
    private record Entry(String id, String kind, JsonNode data, Supplier<JsonNode> current) { }
    private record PreferenceEntry(PreferenceSnapshot snapshot, Supplier<PreferenceSnapshot> current) { }
    public record Dependency(String key, String kind, KnowledgeRetrievalService.Source source, JsonNode snapshot) { }
    public record Citation(String evidenceId, String kind, String courseId, String lessonId, String documentId,
                           long sourceVersion, String chunkId, String title, String heading, String excerpt, JsonNode snapshot) { }
}
