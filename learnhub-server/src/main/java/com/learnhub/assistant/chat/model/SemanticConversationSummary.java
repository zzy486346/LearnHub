package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 严格验证摘要结构与输入溯源；结构通过不代表模型摘要在语义上忠实。 */
final class SemanticConversationSummary {
    static final int MAX_BYTES = 4096;
    private static final int MAX_ITEMS = 8;
    private static final int MAX_VALUE_CODE_POINTS = 400;
    private static final Set<String> TOP_FIELDS = Set.of(
            "schemaVersion", "taskState", "conversationSummary", "unresolvedReferences");
    private static final Set<String> TASK_FIELDS = Set.of(
            "goal", "topic", "constraints", "decisions", "corrections", "openQuestions", "nextStep");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "value", "sourceMessageSeqs", "dependencyKeys", "inferred");
    private static final Pattern EXTERNAL_LINK = Pattern.compile(
            "(?i)(https?://|www\\.|(?:javascript|data|file|ftp):|//[a-z0-9])");

    private SemanticConversationSummary() { }

    static Summary parse(ObjectMapper mapper, String json, Map<Long, Set<String>> allowedSources, Set<Long> userSeqs) {
        Summary summary = parse(mapper, json, allowedSources);
        TaskState state = summary.taskState();
        for (List<Item> items : List.of(state.goal(), state.topic(), state.constraints(), state.decisions(),
                state.corrections(), state.openQuestions(), state.nextStep(), summary.conversationSummary(), summary.unresolvedReferences())) {
            for (Item item : items) if (!item.inferred() && item.sourceMessageSeqs().stream().noneMatch(userSeqs::contains)) throw invalid();
        }
        return summary;
    }

    static Summary parse(ObjectMapper mapper, String json, Map<Long, Set<String>> allowedSources) {
        if (mapper == null || json == null || allowedSources == null
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        try {
            JsonNode root = mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            fields(root, TOP_FIELDS);
            JsonNode version = root.get("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) throw invalid();
            JsonNode task = root.get("taskState");
            fields(task, TASK_FIELDS);
            TaskState state = new TaskState(
                    items(task.get("goal"), allowedSources),
                    items(task.get("topic"), allowedSources),
                    items(task.get("constraints"), allowedSources),
                    items(task.get("decisions"), allowedSources),
                    items(task.get("corrections"), allowedSources),
                    items(task.get("openQuestions"), allowedSources),
                    items(task.get("nextStep"), allowedSources));
            return new Summary(1, state, items(root.get("conversationSummary"), allowedSources),
                    items(root.get("unresolvedReferences"), allowedSources));
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static List<Item> items(JsonNode node, Map<Long, Set<String>> allowedSources) {
        if (node == null || !node.isArray() || node.size() > MAX_ITEMS) throw invalid();
        List<Item> result = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            fields(item, ITEM_FIELDS);
            String value = strictText(item.get("value"), MAX_VALUE_CODE_POINTS);
            JsonNode sequences = item.get("sourceMessageSeqs");
            if (!sequences.isArray() || sequences.isEmpty() || sequences.size() > MAX_ITEMS) throw invalid();
            List<Long> sourceMessageSeqs = new ArrayList<>(sequences.size());
            Set<String> allowedDependencies = new HashSet<>();
            Set<Long> uniqueSequences = new HashSet<>();
            for (JsonNode sequence : sequences) {
                if (!sequence.isIntegralNumber() || !sequence.canConvertToLong()) throw invalid();
                long seq = sequence.longValue();
                Set<String> dependencies = allowedSources.get(seq);
                if (dependencies == null || !uniqueSequences.add(seq)) throw invalid();
                sourceMessageSeqs.add(seq);
                dependencies.forEach(key -> allowedDependencies.add(strictKey(key)));
            }
            JsonNode keys = item.get("dependencyKeys");
            if (!keys.isArray() || keys.size() > MAX_ITEMS) throw invalid();
            List<String> dependencyKeys = new ArrayList<>(keys.size());
            Set<String> uniqueKeys = new HashSet<>();
            for (JsonNode keyNode : keys) {
                String key = strictText(keyNode, 200);
                if (!allowedDependencies.contains(key) || !uniqueKeys.add(key)) throw invalid();
                dependencyKeys.add(key);
            }
            JsonNode inferred = item.get("inferred");
            if (!inferred.isBoolean()) throw invalid();
            result.add(new Item(value, List.copyOf(sourceMessageSeqs), List.copyOf(dependencyKeys),
                    inferred.booleanValue()));
        }
        return List.copyOf(result);
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!expected.contains(name)) throw invalid(); });
        if (!expected.stream().allMatch(node::has)) throw invalid();
    }

    private static String strictKey(String value) {
        if (value == null) throw invalid();
        return strictText(new com.fasterxml.jackson.databind.node.TextNode(value), 200);
    }

    private static String strictText(JsonNode node, int maxCodePoints) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints
                || EXTERNAL_LINK.matcher(value).find()) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
            } else if (Character.isLowSurrogate(current)) {
                throw invalid();
            }
        }
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_SUMMARY_INVALID", "会话摘要结构或溯源无效");
    }

    record Summary(int schemaVersion, TaskState taskState, List<Item> conversationSummary,
                   List<Item> unresolvedReferences) { }

    record TaskState(List<Item> goal, List<Item> topic, List<Item> constraints, List<Item> decisions,
                     List<Item> corrections, List<Item> openQuestions, List<Item> nextStep) { }

    record Item(String value, List<Long> sourceMessageSeqs, List<String> dependencyKeys, boolean inferred) { }
}
