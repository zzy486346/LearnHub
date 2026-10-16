package com.learnhub.assistant.model;

public record Usage(long promptTokens, long completionTokens, long totalTokens) {
    public Usage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        if (totalTokens != promptTokens + completionTokens) {
            throw new IllegalArgumentException("totalTokens must equal promptTokens plus completionTokens");
        }
    }
}
