package com.learnhub.assistant.knowledge.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("vector")
@ConditionalOnProperty(name = "learnhub.vector.enabled", havingValue = "true")
public class VectorConfiguration {
    @Bean(destroyMethod = "close")
    CourseVectorRuntime courseVectorRuntime(VectorProjectionSync sync,
            @Value("${learnhub.vector.hybrid-enabled:false}") boolean hybrid,
            @Value("${learnhub.vector.sync-enabled:false}") boolean indexing,
            @Value("${learnhub.vector.jdbc-url:jdbc:postgresql://127.0.0.1:15432/learnhub_vector}") String url,
            @Value("${learnhub.vector.username:learnhub_vector_app}") String username,
            @Value("${learnhub.vector.password:}") String password,
            @Value("${learnhub.vector.embedding-provider:LOCAL}") String provider,
            @Value("${learnhub.vector.siliconflow.endpoint:https://api.siliconflow.cn/v1/embeddings}") String endpoint,
            @Value("${learnhub.vector.siliconflow.api-key:}") String key,
            @Value("${learnhub.vector.siliconflow.timeout:10s}") String timeoutValue) {
        VectorProjectionSync.modelIdFor(provider);
        java.time.Duration timeout = org.springframework.boot.convert.DurationStyle.detectAndParse(timeoutValue);
        java.util.function.Supplier<CourseEmbedding> factory = "SILICONFLOW".equals(provider)
                ? () -> new SiliconFlowBgeEmbedding(java.net.URI.create(endpoint), key, timeout)
                : BgeChineseEmbedding::new;
        return new CourseVectorRuntime(sync, hybrid, indexing, url, username, password, factory);
    }
}
