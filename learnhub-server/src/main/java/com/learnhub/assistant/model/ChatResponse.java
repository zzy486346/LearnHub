package com.learnhub.assistant.model;

import java.util.List;

public record ChatResponse(
        String text,
        List<ToolCall> toolCalls,
        Usage usage,
        String finishReason,
        String modelMode) {
    public ChatResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (usage == null) throw new IllegalArgumentException("usage must not be null");
        if (finishReason == null || finishReason.isBlank()) {
            throw new IllegalArgumentException("finishReason must not be blank");
        }
        if (modelMode == null || modelMode.isBlank()) {
            throw new IllegalArgumentException("modelMode must not be blank");
        }
    }
}
