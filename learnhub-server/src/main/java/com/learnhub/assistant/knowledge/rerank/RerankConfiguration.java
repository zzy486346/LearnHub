package com.learnhub.assistant.knowledge.rerank;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "learnhub.rerank.enabled", havingValue = "true")
public class RerankConfiguration {
    @Bean(destroyMethod = "close")
    SiliconFlowReranker siliconFlowReranker(
            @Value("${learnhub.rerank.endpoint:https://api.siliconflow.cn/v1/rerank}") String endpoint,
            @Value("${learnhub.rerank.api-key:}") String overrideKey,
            @Value("${LEARNHUB_VECTOR_SILICONFLOW_API_KEY:}") String sharedKey,
            @Value("${learnhub.rerank.timeout:5s}") String timeout,
            @Value("${learnhub.rerank.candidates:20}") int candidates) {
        URI uri = URI.create(endpoint);
        Duration duration = DurationStyle.detectAndParse(timeout);
        String key = overrideKey.isBlank() ? sharedKey : overrideKey;
        SiliconFlowReranker.validateConfiguration(uri, key, duration, candidates);
        return new SiliconFlowReranker(uri, key, duration, candidates);
    }
}
