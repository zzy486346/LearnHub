package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class CatalogMaterialImportSafetyTest {
    @Test
    void rejectsRemoteTargetsAndWrongDatabaseBeforeContextRefresh() {
        for (Map<String, Object> override : java.util.List.of(
                Map.<String, Object>of("spring.datasource.url", "jdbc:mysql://example.invalid:3306/learnhub"),
                Map.<String, Object>of("spring.datasource.url", "jdbc:mysql://localhost:3306/another_database"),
                Map.<String, Object>of("spring.elasticsearch.uris", "http://example.invalid:9200"),
                Map.<String, Object>of("spring.data.redis.host", "example.invalid"))) {
            try (var context = context(override)) {
                assertThatThrownBy(() -> new CatalogMaterialImportIntegrationTest.LocalOnly().initialize(context))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("拒绝启动上下文");
                assertThat(context.isActive()).isFalse();
            }
        }
    }

    @Test
    void localContextRemovesSchedulingInfrastructureBeforeSingletonCreation() {
        try (var context = context(Map.of())) {
            String scheduling = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
            context.registerBeanDefinition(scheduling, new RootBeanDefinition(ScheduledAnnotationBeanPostProcessor.class));
            new CatalogMaterialImportIntegrationTest.LocalOnly().initialize(context);
            context.refresh();
            assertThat(context.containsBean(scheduling)).isFalse();
        }
    }

    private GenericApplicationContext context(Map<String, Object> overrides) {
        var context = new GenericApplicationContext();
        Map<String, Object> values = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/learnhub?characterEncoding=utf8",
                "spring.elasticsearch.uris", "http://127.0.0.1:9200",
                "spring.data.redis.host", "localhost"));
        values.putAll(overrides);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("importSafety", values));
        return context;
    }
}
