package com.learnhub.assistant.chat.model;

import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 SDK 消息按不可拆分的对话边界分组，并严格校验工具调用与结果。 */
final class ChatMessageGroups {
    private ChatMessageGroups() { }

    static List<Group> groups(List<ChatMessage> messages) {
        if (messages == null) throw invalid();
        List<Draft> drafts = new ArrayList<>();
        Draft active = null;
        for (ChatMessage message : messages) {
            if (message == null) throw invalid();
            if (message instanceof SystemMessage) {
                active = finishBeforeBoundary(drafts, active);
                drafts.add(Draft.system(message));
            } else if (message instanceof UserMessage) {
                active = finishBeforeBoundary(drafts, active);
                active = Draft.conversation(message);
            } else if (message instanceof AiMessage assistant) {
                if (active == null || !active.awaitingModel()) throw invalid();
                active.addAssistant(assistant);
            } else if (message instanceof ToolExecutionResultMessage result) {
                if (active == null) throw invalid();
                active.addResult(result);
            } else {
                throw invalid();
            }
        }
        if (active != null) {
            if (active.hasPendingTools()) throw invalid();
            drafts.add(active);
        }

        int current = -1;
        for (int index = drafts.size() - 1; index >= 0; index--) {
            if (drafts.get(index).conversation) {
                current = index;
                break;
            }
        }
        List<Group> result = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            result.add(new Group(List.copyOf(draft.messages), index == current, draft.plainRound()));
        }
        return List.copyOf(result);
    }

    private static Draft finishBeforeBoundary(List<Draft> groups, Draft active) {
        if (active == null) return null;
        if (!active.complete()) throw invalid();
        groups.add(active);
        return null;
    }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_CONTEXT_INVALID", "助理上下文消息组不完整或工具结果无法配对");
    }

    record Group(List<ChatMessage> messages, boolean current, boolean plainRound) { }

    private static final class Draft {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final boolean conversation;
        private final Map<String, String> pendingTools = new LinkedHashMap<>();
        private final Set<String> usedToolIds = new LinkedHashSet<>();
        private boolean complete;
        private boolean usedTools;

        private Draft(ChatMessage first, boolean conversation) {
            this.conversation = conversation;
            messages.add(first);
        }

        static Draft system(ChatMessage message) { return new Draft(message, false); }
        static Draft conversation(ChatMessage message) { return new Draft(message, true); }

        boolean awaitingModel() { return conversation && !complete && pendingTools.isEmpty(); }
        boolean hasPendingTools() { return !pendingTools.isEmpty(); }
        boolean complete() { return complete; }

        void addAssistant(AiMessage assistant) {
            messages.add(assistant);
            if (!assistant.hasToolExecutionRequests()) {
                complete = true;
                return;
            }
            usedTools = true;
            List<ToolExecutionRequest> calls = assistant.toolExecutionRequests();
            if (calls == null || calls.isEmpty()) throw invalid();
            for (ToolExecutionRequest call : calls) {
                if (call == null || blank(call.id()) || blank(call.name()) || !usedToolIds.add(call.id())
                        || pendingTools.putIfAbsent(call.id(), call.name()) != null) throw invalid();
            }
        }

        void addResult(ToolExecutionResultMessage result) {
            if (!conversation || pendingTools.isEmpty() || blank(result.id()) || blank(result.toolName())) throw invalid();
            String expectedName = pendingTools.remove(result.id());
            if (expectedName == null || !expectedName.equals(result.toolName())) throw invalid();
            messages.add(result);
        }

        boolean plainRound() {
            return conversation && complete && !usedTools && messages.size() == 2
                    && messages.get(0) instanceof UserMessage && messages.get(1) instanceof AiMessage;
        }

        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}
