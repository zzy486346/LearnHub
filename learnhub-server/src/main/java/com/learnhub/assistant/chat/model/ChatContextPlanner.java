package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 同步、无外部调用的上下文预算器。字节按 Token 上界保守预留，最终仍由运行 guard 扣账。 */
final class ChatContextPlanner {
    private static final int PROTOCOL_BYTES = 2048;
    private final ObjectMapper mapper;

    ChatContextPlanner(ObjectMapper mapper) { this.mapper = mapper; }

    int capacity(int inputLimit, int runLimit, long reservedTokens, int outputTokens) {
        return (int) Math.max(0, Math.min(inputLimit, (long) runLimit - reservedTokens - outputTokens));
    }

    int bytes(List<ChatMessage> messages, Object toolDefinitions) {
        long total = PROTOCOL_BYTES + utf8(json(messages.stream().map(Object::toString).toList()));
        if (toolDefinitions != null) total += utf8(json(toolDefinitions));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 历史只消费剩余空间的一半，为后续观察留空间；不保证后续每个工具批次都能装下。 */
    int historyAllowance(int capacity, int requiredBytes) {
        return Math.max(0, capacity - requiredBytes) / 2;
    }

    int roundBytes(String user, String assistant, String dependencyJson) {
        // 依赖作为完整 JSON 再注入；转义后的成本及消息封装均计入，不截断其内部文本。
        long total = bytes(List.of(UserMessage.from(user), AiMessage.from(assistant)), null)
                - PROTOCOL_BYTES + utf8(json(dependencyJson)) + 256L;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 仅移除当前问题之前的完整历史对；工具调用/结果、当前问题和规则不可拆开。 */
    Plan fit(List<ChatMessage> original, Object tools, int inputLimit, int capacity) {
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
        return new Plan(List.copyOf(messages), size, removed, summaryRemoved);
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
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) if (messages.get(i) instanceof UserMessage) lastUser = i;
        for (int i = 0; i + 1 < lastUser; i++) {
            if (messages.get(i) instanceof UserMessage && messages.get(i + 1) instanceof AiMessage ai
                    && !ai.hasToolExecutionRequests()) {
                messages.subList(i, i + 2).clear();
                return true;
            }
            // 规则可跳过，遇到工具链不猜测其边界。
            if (!(messages.get(i) instanceof SystemMessage)) break;
        }
        return false;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new BusinessException("ASSISTANT_OUTPUT_INVALID", "上下文无法序列化"); }
    }

    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    record Plan(List<ChatMessage> messages, int inputBytes, int droppedRounds, int droppedSummaryEntries) { }
}
