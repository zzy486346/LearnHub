package com.learnhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.runtime.AssistantLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/** 无需读取开发者 .env、连接中间件或调用模型即可验证默认值和环境覆盖。 */
class EnvironmentDefaultsTest {
    @Test void aiAndAssistantYamlDefaultsMatchExistingCodeDefaults() {
        var sources = sources(Map.of());
        var binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        assertThat(binder.bind("learnhub.ai", Bindable.of(AiProperties.class)).get())
                .usingRecursiveComparison().isEqualTo(new AiProperties());
        assertThat(binder.bind("learnhub.assistant", Bindable.of(AssistantLimits.class)).get())
                .usingRecursiveComparison().isEqualTo(new AssistantLimits());
        var resolver = new PropertySourcesPropertyResolver(sources);
        assertThat(resolver.getProperty("learnhub.search.sync-delay")).isEqualTo("5000");
        assertThat(resolver.getProperty("learnhub.search.sync-batch-size")).isEqualTo("100");
        assertThat(resolver.getProperty("learnhub.seckill.consumer-confirmation-timeout")).isEqualTo("PT1M");
        assertThat(resolver.getProperty("learnhub.like.reconciliation-delay")).isEqualTo("3600000");
        assertThat(resolver.getProperty("learnhub.payment.pending-timeout")).isEqualTo("PT30M");
        assertThat(resolver.getProperty("learnhub.vector.min-semantic-score")).isEqualTo("0.815");
    }

    @Test void explicitEnvironmentOverridesAndSafeFeatureDefaultsRemainEffective() {
        var sources = sources(Map.of("LEARNHUB_ASSISTANT_MAX_INPUT_BYTES", "65536",
                "LEARNHUB_ASSISTANT_MODEL_WINDOW_TOKENS", "1048576", "LEARNHUB_ASSISTANT_DAILY_MODEL_CALLS", "8",
                "LEARNHUB_AI_MODE", "FAKE", "LEARNHUB_ASSISTANT_CHAT_ENABLED", "true"));
        var binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        var limits = binder.bind("learnhub.assistant", Bindable.of(AssistantLimits.class)).get();
        assertThat(limits.getMaxInputBytes()).isEqualTo(65536);
        assertThat(limits.getModelWindowTokens()).isEqualTo(1048576);
        assertThat(limits.getDailyModelCalls()).isEqualTo(8);
        assertThat(limits.isContextRedesignEnabled()).isFalse();
        assertThat(limits.isValidConfiguration()).isTrue();
        assertThat(binder.bind("learnhub.ai", Bindable.of(AiProperties.class)).get().resolvedMode()).isEqualTo(AiProperties.Mode.FAKE);
        var resolver = new PropertySourcesPropertyResolver(sources);
        assertThat(resolver.getProperty("learnhub.assistant.chat-enabled")).isEqualTo("true");
        assertThat(resolver.getProperty("learnhub.assistant.langchain-enabled")).isEqualTo("false");
        assertThat(resolver.getProperty("learnhub.assistant.memory.auto-extract-enabled")).isEqualTo("false");
    }

    private MutablePropertySources sources(Map<String, Object> external) {
        // 测试 classpath 的 application.yml 是独立测试配置，显式加载生产文件。
        var production = new YamlPropertiesFactoryBean();
        production.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"),
                new org.springframework.core.io.FileSystemResource("src/main/resources/application-vector.yml"));
        var sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("external-fixture", external));
        sources.addLast(new PropertiesPropertySource("application-defaults", production.getObject()));
        return sources;
    }
}
