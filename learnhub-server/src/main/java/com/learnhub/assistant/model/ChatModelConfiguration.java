package com.learnhub.assistant.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class ChatModelConfiguration {
    @Bean
    ChatModelClient chatModelClient(AiProperties properties, ObjectProvider<ObjectMapper> objectMappers) {
        return switch (properties.resolvedMode()) {
            case DISABLED -> new DisabledChatModelClient();
            case FAKE -> new FakeChatModelClient();
            case DEEPSEEK -> {
                properties.validateForDeepSeek();
                ObjectMapper mapper = objectMappers.getIfAvailable();
                if (mapper == null) throw new IllegalStateException("ObjectMapper is required for DeepSeek mode");
                yield new DeepSeekChatModelClient(mapper, properties);
            }
        };
    }
}
