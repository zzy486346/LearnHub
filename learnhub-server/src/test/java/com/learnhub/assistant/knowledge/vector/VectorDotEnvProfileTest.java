package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VectorDotEnvProfileTest {
    @TempDir Path directory;

    @Test void importedUppercaseProfileSettingLoadsVectorConfigurationWithoutExportingShellVariables() throws Exception {
        Path local = directory.resolve("local.properties");
        Files.writeString(local, """
                SPRING_PROFILES_ACTIVE=local,vector
                LEARNHUB_VECTOR_ENABLED=true
                LEARNHUB_VECTOR_PASSWORD=test-only
                """);
        runner(local).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CourseVectorRuntime.class);
            assertThat(context.getEnvironment().getActiveProfiles()).contains("local", "vector");
            assertThat(context.getEnvironment().getProperty("learnhub.vector.jdbc-url"))
                    .isEqualTo("jdbc:postgresql://127.0.0.1:15432/learnhub_vector");
        });
    }

    @Test void absentLocalProfileAndFlagKeepVectorDisabled() throws Exception {
        Path empty = directory.resolve("empty.properties");
        Files.writeString(empty, "");
        runner(empty).run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseVectorRuntime.class));
    }

    private ApplicationContextRunner runner(Path local) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.config.location=file:src/main/resources/application.yml",
                        "spring.config.import=" + local.toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(VectorConfiguration.class)
                .withBean(VectorProjectionSync.class, () -> mock(VectorProjectionSync.class));
    }
}
