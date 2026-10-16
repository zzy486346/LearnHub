package com.learnhub.assistant.model;

public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("tool call id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool call name must not be blank");
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("tool call arguments must not be blank");
        }
    }
}
