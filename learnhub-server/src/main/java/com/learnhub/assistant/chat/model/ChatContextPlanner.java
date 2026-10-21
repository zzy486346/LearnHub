package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;

/** 同步上下文选择器；只消费输入测量和外部给定容量，不读取或修改累计成本账。 */
final class ChatContextPlanner {
    private final ObjectMapper mapper;
    private final ChatInputMeter meter;

    ChatContextPlanner(ObjectMapper mapper) {
        this.mapper = mapper;
        this.meter = new ChatInputMeter(mapper, "planning-only");
    }

    int bytes(List<ChatMessage> messages, Object toolDefinitions) {
        return meter.estimateBytes(messages, toolDefinitions);
    }

    /** 历史只消费剩余空间的一半，为后续观察留空间；不保证后续每个工具批次都能装下。 */
    int historyAllowance(int capacity, int requiredBytes) {
        return Math.max(0, capacity - requiredBytes) / 2;
    }

    int roundBytes(String user, String assistant, String dependencyJson) {
        return meter.roundBytes(user, assistant, dependencyJson);
    }

    /** 仅移除当前问题之前的完整历史对；工具调用/结果、当前问题和规则不可拆开。 */
    Plan fit(List<ChatMessage> original, Object tools, int inputLimit, int capacity) {
        ChatMessageGroups.groups(original);
        List<ChatMessage> messages = new ArrayList<>(original);
        int removed = 0;
        int summaryRemoved = 0;
        int size = bytes(messages, tools);
        while (size > capacity && removeOldestSummaryEntry(messages)) {
            summaryRemoved++;
            size = bytes(messages, tools);
        }
        while (size > capacity && removeOldestRound(messages)) {
            removed++;
            size = bytes(messages, tools);
        }
        if (size > inputLimit) throw new BusinessException("AI_MODEL_INPUT_TOO_LARGE", "模型输入超过本地 UTF-8 字节预算");
        if (size > capacity) throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "本次运行剩余预算不足以容纳当前问题与证据");
        List<String> reasons = new ArrayList<>();
        if (summaryRemoved > 0) reasons.add("DROPPED_OLD_SUMMARY_ENTRIES");
        if (removed > 0) reasons.add("DROPPED_OLD_COMPLETE_ROUNDS");
        if (reasons.isEmpty()) reasons.add("WITHIN_CAPACITY");
        return new Plan(List.copyOf(messages), size, removed, summaryRemoved, List.copyOf(reasons));
    }

    private boolean removeOldestSummaryEntry(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof UserMessage user)) continue;
            try {
                var root = mapper.readTree(user.singleText());
                var entries = root.path("conversationSummary").path("userStatements");
                if (!root.path("question").isTextual() || !entries.isArray() || entries.isEmpty()) return false;
                ((com.fasterxml.jackson.databind.node.ArrayNode) entries).remove(0);
                messages.set(i, UserMessage.from(mapper.writeValueAsString(root)));
                return true;
            } catch (Exception notStructuredContext) { return false; }
        }
        return false;
    }

    private boolean removeOldestRound(List<ChatMessage> messages) {
        int offset = 0;
        for (var group : ChatMessageGroups.groups(messages)) {
            if (!group.current() && group.plainRound()) {
                messages.subList(offset, offset + group.messages().size()).clear();
                return true;
            }
            // 阶段 A 保留旧选择行为：规则可跳过，遇到工具组不淘汰它或更后的组。
            if (!(group.messages().get(0) instanceof SystemMessage)) break;
            offset += group.messages().size();
        }
        return false;
    }

    record Plan(List<ChatMessage> messages, int inputBytes, int droppedRounds, int droppedSummaryEntries,
                List<String> selectionReasons) { }
}
