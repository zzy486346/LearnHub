package com.learnhub.assistant.runtime;

public record AssistantRunSummary(
        String requestId,
        int modelCalls,
        int toolDecisionRounds,
        int toolCalls,
        long reservedTokens,
        long actualPromptTokens,
        long actualCompletionTokens,
        long actualTotalTokens,
        boolean usageComplete,
        String status,
        long elapsedMillis) {
}
