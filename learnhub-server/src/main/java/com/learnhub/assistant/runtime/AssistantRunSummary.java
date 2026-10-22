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
        long elapsedMillis,
        int prepaidInputTokens,
        int prepaidOutputTokens) {

    public AssistantRunSummary(String requestId, int modelCalls, int toolDecisionRounds, int toolCalls,
            long reservedTokens, long actualPromptTokens, long actualCompletionTokens, long actualTotalTokens,
            boolean usageComplete, String status, long elapsedMillis) {
        this(requestId, modelCalls, toolDecisionRounds, toolCalls, reservedTokens, actualPromptTokens,
                actualCompletionTokens, actualTotalTokens, usageComplete, status, elapsedMillis, 0, 0);
    }
}
