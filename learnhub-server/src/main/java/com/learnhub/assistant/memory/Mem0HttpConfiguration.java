package com.learnhub.assistant.memory;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "learnhub.assistant.memory", name = "enabled", havingValue = "true")
public class Mem0HttpConfiguration {
    @Bean
    Mem0HttpClient mem0HttpClient(
            @Value("${learnhub.assistant.memory.base-url:${LEARNHUB_ASSISTANT_MEMORY_BASE_URL:http://127.0.0.1:18280}}") String base,
            @Value("${learnhub.assistant.memory.service-token:${LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN:}}") String token,
            @Value("${learnhub.assistant.memory.timeout:${LEARNHUB_ASSISTANT_MEMORY_TIMEOUT:PT2S}}") String timeout) {
        return new Mem0HttpClient(URI.create(base), token, Duration.parse(timeout));
    }
}
