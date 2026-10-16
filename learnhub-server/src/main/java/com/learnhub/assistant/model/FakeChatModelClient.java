package com.learnhub.assistant.model;

final class FakeChatModelClient implements ChatModelClient {
    static final int MAX_PROMPT_LENGTH = 4_000;
    static final String RESPONSE = "[FAKE] AI 模型模拟响应";
    static final String JSON_RESPONSE = "{\"insufficientEvidence\":true,\"answerSegments\":[]}";

    @Override
    public ChatResponse generate(ChatRequest request) {
        int promptLength = request.messages().stream()
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        if (promptLength > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt must not exceed " + MAX_PROMPT_LENGTH + " characters");
        }
        return new ChatResponse(
                request.jsonOutput() ? JSON_RESPONSE : RESPONSE,
                java.util.List.of(),
                new Usage(0, 0, 0),
                "stop",
                "FAKE");
    }
}
