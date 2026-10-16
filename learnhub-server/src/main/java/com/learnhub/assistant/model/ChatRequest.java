package com.learnhub.assistant.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record ChatRequest(
        List<ChatMessage> messages,
        List<Map<String, Object>> tools,
        int maxOutputTokens,
        Duration timeout,
        boolean jsonOutput) {
    public static final int MAX_OUTPUT_TOKENS = 2_048;

    public ChatRequest {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and " + MAX_OUTPUT_TOKENS);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
