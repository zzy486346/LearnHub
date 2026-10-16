package com.learnhub.assistant.model;

import java.util.List;
import java.util.Set;

public record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    public ChatMessage {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("message role must be system, user, assistant, or tool");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        boolean hasContent = content != null && !content.isBlank();
        switch (role) {
            case "system", "user" -> {
                if (!hasContent) throw new IllegalArgumentException(role + " message content must not be blank");
                if (toolCallId != null || !toolCalls.isEmpty()) {
                    throw new IllegalArgumentException(role + " message cannot contain tool call fields");
                }
            }
            case "assistant" -> {
                if (!hasContent && toolCalls.isEmpty()) {
                    throw new IllegalArgumentException("assistant message needs content or tool calls");
                }
                if (toolCallId != null) throw new IllegalArgumentException("assistant message cannot have toolCallId");
            }
            case "tool" -> {
                if (!hasContent) throw new IllegalArgumentException("tool message content must not be blank");
                if (toolCallId == null || toolCallId.isBlank()) {
                    throw new IllegalArgumentException("tool message toolCallId must not be blank");
                }
                if (!toolCalls.isEmpty()) throw new IllegalArgumentException("tool message cannot request tool calls");
            }
            default -> throw new IllegalStateException("Unexpected role: " + role);
        }
    }
}
