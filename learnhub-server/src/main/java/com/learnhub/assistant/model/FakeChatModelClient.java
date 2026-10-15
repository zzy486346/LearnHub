package com.learnhub.assistant.model;

final class FakeChatModelClient implements ChatModelClient {
    static final int MAX_PROMPT_LENGTH = 4_000;
    static final String RESPONSE = "[FAKE] AI 模型模拟响应";

    @Override
    public String generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt must not exceed " + MAX_PROMPT_LENGTH + " characters");
        }
        return RESPONSE;
    }
}
