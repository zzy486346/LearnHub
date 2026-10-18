package com.learnhub.experiments;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeanCompatibilityTest {

    @Test
    void springBoot335DependenciesCanCreatePlainJavaLangChain4jBeansOnJdk17() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ModelConfiguration.class)) {
            ChatModel sync = context.getBean(ChatModel.class);
            StreamingChatModel streaming = context.getBean(StreamingChatModel.class);

            assertThat(sync).isInstanceOf(OpenAiChatModel.class);
            assertThat(streaming).isInstanceOf(OpenAiStreamingChatModel.class);
            assertThat(sync).isNotInstanceOf(AutoCloseable.class);
            assertThat(streaming).isNotInstanceOf(AutoCloseable.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return OpenAiChatModel.builder()
                    .baseUrl("http://127.0.0.1:1/v1")
                    .apiKey("local-mock-key")
                    .modelName("mock-model")
                    .timeout(Duration.ofMillis(100))
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }

        @Bean
        StreamingChatModel streamingChatModel() {
            return OpenAiStreamingChatModel.builder()
                    .baseUrl("http://127.0.0.1:1/v1")
                    .apiKey("local-mock-key")
                    .modelName("mock-model")
                    .timeout(Duration.ofMillis(100))
                    .accumulateToolCallId(false)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
    }
}
