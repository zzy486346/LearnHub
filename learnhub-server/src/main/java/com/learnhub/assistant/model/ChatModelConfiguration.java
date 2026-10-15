package com.learnhub.assistant.model;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatModelConfiguration {
    private final Mode mode;

    public ChatModelConfiguration(@Value("${learnhub.ai.mode:DISABLED}") String configuredMode) {
        this.mode = Mode.parse(configuredMode);
    }

    @Bean
    ChatModelClient chatModelClient() {
        return switch (mode) {
            case DISABLED -> new DisabledChatModelClient();
            case FAKE -> new FakeChatModelClient();
        };
    }

    private enum Mode {
        DISABLED,
        FAKE;

        private static Mode parse(String configuredMode) {
            String normalized = configuredMode == null
                    ? DISABLED.name()
                    : configuredMode.trim().toUpperCase(Locale.ROOT);
            try {
                return Mode.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException(
                        "Unsupported learnhub.ai.mode: " + configuredMode + ". Expected DISABLED or FAKE");
            }
        }
    }
}
