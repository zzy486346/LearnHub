package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.memory.PreferenceMemorySource;
import com.learnhub.assistant.memory.PreferenceSnapshot;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.tool.AssistantTools;
import com.learnhub.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** sources_json 的唯一有界解析契约：旧材料数组可读，新依赖保留全局 E# 顺序。 */
@Component
public class ChatEvidenceCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_FIELDS = Set.of("documentId", "sourceVersion", "chunkId", "courseId",
            "lessonId", "title", "heading", "chunkOrder", "text", "score");
    private final AssistantTools tools;
    private final ObjectProvider<PreferenceMemorySource> preferences;

    public ChatEvidenceCodec(AssistantTools tools) { this(tools, null); }

    @Autowired
    public ChatEvidenceCodec(AssistantTools tools, ObjectProvider<PreferenceMemorySource> preferences) {
        this.tools = tools;
        this.preferences = preferences;
    }

    /** 兼容已有四参 HistoryService 的纯材料测试；结构化历史必须使用生产注入的 codec。 */
    static ChatEvidenceCodec legacyOnly() { return new ChatEvidenceCodec(null); }

    public String encode(EvidenceBook book) {
        if (book == null) throw invalid();
        var entries = JSON.createArrayNode();
        for (var dependency : book.exportDependencies()) {
            var entry = entries.addObject();
            entry.put("key", dependency.key());
            entry.put("kind", dependency.kind());
            if ("MATERIAL".equals(dependency.kind())) entry.set("source", JSON.valueToTree(dependency.source()));
            else entry.set("snapshot", dependency.snapshot().deepCopy());
        }
        var preferenceDependencies = book.exportPreferenceDependencies();
        var envelope = JSON.createObjectNode().put("version", preferenceDependencies.isEmpty() ? 1 : 2);
        envelope.set("entries", entries);
        if (!preferenceDependencies.isEmpty()) envelope.set("preferences", JSON.valueToTree(preferenceDependencies));
        try {
            String encoded = JSON.writeValueAsString(envelope);
            parse(encoded);
            return encoded;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }

    public List<KnowledgeRetrievalService.Source> restoreInto(long owner, long courseId, String json,
                                                            EvidenceBook book) {
        if (owner <= 0 || courseId <= 0 || book == null) throw invalid();
        var parsed = parseEnvelope(json);
        // 先验证全部偏好，不能丢弃失效的一条后继续复用剩余历史回答。
        PreferenceMemorySource preferenceSource = parsed.preferences().isEmpty() || preferences == null
                ? null : preferences.getIfAvailable();
        for (PreferenceSnapshot preference : parsed.preferences()) {
            if (preferenceSource == null || !preference.equals(preferenceSource.current(owner, preference.id()))) throw invalid();
        }
        for (PreferenceSnapshot preference : parsed.preferences()) {
            var currentSource = preferenceSource;
            book.registerPreference(preference, () -> currentSource.current(owner, preference.id()));
        }
        List<KnowledgeRetrievalService.Source> materials = new ArrayList<>();
        for (ParsedEntry entry : parsed.entries()) {
            if ("MATERIAL".equals(entry.kind())) {
                var source = entry.source();
                if (source.courseId() == null || !source.courseId().equals(Long.toString(courseId))
                        || source.lessonId() == null || source.title() == null || source.text() == null)
                    throw invalid();
                book.material(source);
                materials.add(source);
            } else {
                if (tools == null) throw invalid();
                tools.restoreEvidence(owner, courseId, entry.kind(), entry.snapshot(), book);
            }
        }
        book.check();
        return List.copyOf(materials);
    }

    /** 静态解析只校验存储契约；权限、内容和 supplier 均须在 restoreInto 中重建复核。 */
    public static List<ParsedEntry> parse(String json) {
        return parseEnvelope(json).entries();
    }

    private static ParsedEvidence parseEnvelope(String json) {
        if (json == null || json.isBlank() || json.length() > 100_000) throw invalid();
        try {
            JsonNode root = JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null) throw invalid();
            boolean legacy = root.isArray();
            JsonNode entries;
            List<PreferenceSnapshot> preferenceDependencies = List.of();
            if (legacy) entries = root;
            else {
                fields(root, Set.of("version", "entries", "preferences"), Set.of("version", "entries"));
                if (!root.get("version").isIntegralNumber() || !root.get("version").canConvertToInt()
                        || !Set.of(1, 2).contains(root.get("version").intValue())) throw invalid();
                if (root.get("version").intValue() == 1 && root.has("preferences")) throw invalid();
                if (root.get("version").intValue() == 2) {
                    if (!root.has("preferences")) throw invalid();
                    preferenceDependencies = preferenceSnapshots(root.get("preferences"));
                }
                entries = root.get("entries");
            }
            if (!entries.isArray() || entries.size() > 20) throw invalid();
            List<ParsedEntry> result = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (JsonNode node : entries) {
                String kind;
                String key;
                KnowledgeRetrievalService.Source source = null;
                JsonNode snapshot = null;
                if (legacy) {
                    source = source(node, false);
                    kind = "MATERIAL";
                    key = "material:" + source.chunkId();
                } else {
                    fields(node, Set.of("key", "kind", "source", "snapshot"), Set.of("key", "kind"));
                    kind = text(node.get("kind"));
                    key = text(node.get("key"));
                    if ("MATERIAL".equals(kind)) {
                        if (!node.has("source") || node.has("snapshot")) throw invalid();
                        source = source(node.get("source"), true);
                        if (!key.equals("material:" + source.chunkId())) throw invalid();
                    } else {
                        if (!Set.of("OUTLINE", "PROGRESS", "COURSE").contains(kind)
                                || node.has("source") || !node.has("snapshot")) throw invalid();
                        snapshot = node.get("snapshot");
                        snapshot(kind, snapshot);
                        if (!key.equals(kind.toLowerCase(java.util.Locale.ROOT) + ":" + text(snapshot.get("courseId"))))
                            throw invalid();
                    }
                }
                if (!keys.add(key)) throw invalid();
                result.add(new ParsedEntry("E" + (result.size() + 1), key, kind, source,
                        snapshot == null ? null : snapshot.deepCopy()));
            }
            return new ParsedEvidence(List.copyOf(result), preferenceDependencies);
        } catch (BusinessException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }

    private static List<PreferenceSnapshot> preferenceSnapshots(JsonNode nodes) {
        if (nodes == null || !nodes.isArray() || nodes.size() > 10) throw invalid();
        List<PreferenceSnapshot> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        var names = Set.of("id", "version", "category", "text");
        for (JsonNode node : nodes) {
            fields(node, names, names);
            String id = text(node.get("id"));
            if (!UUID.fromString(id).toString().equals(id) || !identities.add(id)) throw invalid();
            JsonNode version = node.get("version");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() <= 0) throw invalid();
            String category = text(node.get("category"));
            if (!Set.of("STYLE", "PACE", "LEVEL", "GOAL").contains(category)) throw invalid();
            String value = text(node.get("text"));
            if (value.length() > 200) throw invalid();
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (Character.isHighSurrogate(current)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(current)) throw invalid();
            }
            result.add(new PreferenceSnapshot(id, version.longValue(), category, value));
        }
        return List.copyOf(result);
    }

    private static KnowledgeRetrievalService.Source source(JsonNode node, boolean full) throws Exception {
        fields(node, SOURCE_FIELDS, full ? Set.of("documentId", "sourceVersion", "chunkId", "courseId", "lessonId",
                "title", "heading", "chunkOrder", "text", "score") : Set.of("documentId", "sourceVersion", "chunkId"));
        positive(node.get("documentId"));
        positive(node.get("chunkId"));
        positive(node.get("sourceVersion"));
        for (String key : List.of("courseId", "lessonId")) if (node.has(key)) positive(node.get(key));
        for (String key : List.of("title", "text")) if (node.has(key) && !node.get(key).isTextual()) throw invalid();
        if (node.has("heading") && !node.get("heading").isNull() && !node.get("heading").isTextual()) throw invalid();
        if (node.has("chunkOrder") && (!node.get("chunkOrder").isIntegralNumber()
                || !node.get("chunkOrder").canConvertToInt() || node.get("chunkOrder").intValue() < 0)) throw invalid();
        if (node.has("score") && (!node.get("score").isNumber() || !Double.isFinite(node.get("score").doubleValue())))
            throw invalid();
        return JSON.treeToValue(node, KnowledgeRetrievalService.Source.class);
    }

    private static void snapshot(String kind, JsonNode snapshot) {
        Set<String> names = switch (kind) {
            case "COURSE" -> Set.of("courseId", "title", "instructor");
            case "OUTLINE" -> Set.of("courseId", "chapters");
            case "PROGRESS" -> Set.of("courseId", "progress");
            default -> throw invalid();
        };
        fields(snapshot, names, names);
        positiveText(snapshot.get("courseId"));
        if ("COURSE".equals(kind)) {
            textAllowEmpty(snapshot.get("title")); textAllowEmpty(snapshot.get("instructor"));
        } else if ("OUTLINE".equals(kind)) {
            array(snapshot.get("chapters"));
            for (JsonNode chapter : snapshot.get("chapters")) {
                var chapterFields = Set.of("chapterId", "title", "order", "lessons");
                fields(chapter, chapterFields, chapterFields);
                positiveText(chapter.get("chapterId")); textAllowEmpty(chapter.get("title"));
                nullableNumber(chapter.get("order")); array(chapter.get("lessons"));
                for (JsonNode lesson : chapter.get("lessons")) {
                    var lessonFields = Set.of("lessonId", "title", "order", "durationSeconds", "accessible");
                    fields(lesson, lessonFields, lessonFields);
                    positiveText(lesson.get("lessonId")); textAllowEmpty(lesson.get("title"));
                    nullableNumber(lesson.get("order")); nullableNumber(lesson.get("durationSeconds"));
                    if (!lesson.get("accessible").isBoolean()) throw invalid();
                }
            }
        } else {
            array(snapshot.get("progress"));
            for (JsonNode progress : snapshot.get("progress")) {
                var progressFields = Set.of("lessonId", "positionSeconds", "completed", "lastLearnedAt");
                fields(progress, progressFields, progressFields);
                positiveText(progress.get("lessonId")); nullableNumber(progress.get("positionSeconds"));
                if (!progress.get("completed").isBoolean()) throw invalid();
                if (!progress.get("lastLearnedAt").isNull()) textAllowEmpty(progress.get("lastLearnedAt"));
            }
        }
    }

    private static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
        if (!required.stream().allMatch(node::has)) throw invalid();
    }
    private static void array(JsonNode node) { if (node == null || !node.isArray()) throw invalid(); }
    private static void nullableNumber(JsonNode node) {
        if (node == null || (!node.isNull() && (!node.isIntegralNumber() || !node.canConvertToInt()))) throw invalid();
    }
    private static String text(JsonNode node) {
        String value = textAllowEmpty(node);
        if (value.isBlank()) throw invalid();
        return value;
    }
    private static String textAllowEmpty(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    private static long positiveText(JsonNode node) {
        text(node);
        return positive(node);
    }
    private static long positive(JsonNode node) {
        if (node == null) throw invalid();
        String value = node.isIntegralNumber() ? node.asText() : text(node);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }
    private static BusinessException invalid() {
        return new BusinessException("CHAT_INPUT_INVALID", "回答证据格式无效或超出上限");
    }

    public record ParsedEntry(String evidenceId, String key, String kind,
                              KnowledgeRetrievalService.Source source, JsonNode snapshot) { }
    private record ParsedEvidence(List<ParsedEntry> entries, List<PreferenceSnapshot> preferences) { }
}
