package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class VectorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("vector"))
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(VectorConfiguration.class)
            .withBean(VectorProjectionSync.class, () -> mock(VectorProjectionSync.class))
            .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/not_used",
                    "spring.datasource.username=not_used", "spring.datasource.password=not_used");

    @Test void disabledDoesNotCreateVectorRuntimeOrReplaceMysqlDataSource() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class).doesNotHaveBean(CourseVectorRuntime.class);
            assertThat(((com.zaxxer.hikari.HikariDataSource) context.getBean(DataSource.class)).getJdbcUrl())
                    .startsWith("jdbc:mysql:");
        });
    }

    @Test void enabledUsesLazyPrivateResourcesAndStillLeavesMysqlAsOnlyDataSource() {
        runner.withPropertyValues("learnhub.vector.enabled=true", "learnhub.vector.password=test-local-only")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class).hasSingleBean(CourseVectorRuntime.class);
                    var runtime = context.getBean(CourseVectorRuntime.class);
                    assertThat(ReflectionTestUtils.getField(runtime, "pool")).isNull();
                    assertThat(ReflectionTestUtils.getField(runtime, "embedding")).isNull();
                    runtime.syncDue();
                    verifyNoInteractions(context.getBean(VectorProjectionSync.class));
                    runtime.close(); runtime.close();
                });
    }

    @Test void flagWithoutVectorProfileCannotActivateThePrivateRuntime() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("default"))
                .withPropertyValues("learnhub.vector.enabled=true", "learnhub.vector.password=test-local-only")
                .run(context -> assertThat(context).doesNotHaveBean(CourseVectorRuntime.class));
    }

    @Test void remoteProviderRemainsLazyAndUsesIndependentIdentityAndDimension() {
        runner.withPropertyValues("learnhub.vector.enabled=true", "learnhub.vector.password=test-local-only",
                "learnhub.vector.embedding-provider=SILICONFLOW", "learnhub.vector.siliconflow.api-key=test-only-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class).hasSingleBean(CourseVectorRuntime.class);
                    var runtime = context.getBean(CourseVectorRuntime.class);
                    assertThat(ReflectionTestUtils.getField(runtime, "embedding")).isNull();
                    @SuppressWarnings("unchecked")
                    var factory = (java.util.function.Supplier<CourseEmbedding>) ReflectionTestUtils.getField(runtime, "embeddingFactory");
                    try (var embedding = factory.get()) {
                        assertThat(embedding).isInstanceOf(SiliconFlowBgeEmbedding.class);
                        assertThat(embedding.modelId()).isNotEqualTo(BgeChineseEmbedding.MODEL_ID);
                        assertThat(embedding.dimension()).isEqualTo(1024);
                    }
                    verifyNoInteractions(context.getBean(VectorProjectionSync.class));
                });
    }

    @Test void unknownProviderCannotSilentlyLoadLocalModel() {
        runner.withPropertyValues("learnhub.vector.enabled=true", "learnhub.vector.password=test-local-only",
                "learnhub.vector.embedding-provider=UNKNOWN")
                .run(context -> assertThat(context).hasFailed());
    }
}
