package com.learnhub.assistant.model;

import java.time.Duration;
import java.util.List;

public interface ChatModelClient {
    ChatResponse generate(ChatRequest request);

    default String generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        ChatRequest request = new ChatRequest(
                List.of(new ChatMessage("user", prompt, null, List.of())),
                List.of(),
                512,
                Duration.ofSeconds(30),
                false);
        return generate(request).text();
    }
}
