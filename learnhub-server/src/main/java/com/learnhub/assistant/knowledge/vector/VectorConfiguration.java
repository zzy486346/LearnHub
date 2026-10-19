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
            @Value("${learnhub.vector.password:}") String password) {
        return new CourseVectorRuntime(sync, hybrid, indexing, url, username, password);
    }
}
