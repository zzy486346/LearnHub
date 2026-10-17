package com.learnhub.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 单次、无重试的真实 DeepSeek 烟测；默认关闭，且不会打印凭据或响应正文。 */
@EnabledIfSystemProperty(named = "learnhub.assistant.deepseek.smoke", matches = "true")
class DeepSeekLiveSmokeTest {

    @Test
    void makesOneBoundedStructuredCallWhenLocalCredentialExists() throws Exception {
        String apiKey = localApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "本地配置没有 DeepSeek API Key，真实烟测跳过");

        AiProperties properties = new AiProperties();
        properties.setMode("DEEPSEEK");
        properties.setApiKey(apiKey);
        properties.setModel("deepseek-flash");
        properties.setMaxOutputTokens(128);
        properties.setRequestTimeout(Duration.ofSeconds(10));
        properties.setConnectTimeout(Duration.ofSeconds(5));

        AtomicInteger calls = new AtomicInteger();
        ChatResponse response;
        try (var client = new DeepSeekChatModelClient(new ObjectMapper(), properties)) {
            calls.incrementAndGet();
            response = client.generate(new ChatRequest(List.of(
                    new ChatMessage("system", "只返回 JSON 对象，不要添加解释。", null, List.of()),
                    new ChatMessage("user", "返回一个 status 字段，值为 ok。", null, List.of())),
                    List.of(), 128, Duration.ofSeconds(10), true));
        }

        assertThat(calls).hasValue(1);
        assertThat(response.modelMode()).isEqualTo("DEEPSEEK");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(new ObjectMapper().readTree(response.text()).path("status").asText()).isEqualTo("ok");
        assertThat(response.usage().totalTokens()).isPositive();
        System.out.printf("DEEPSEEK_SMOKE calls=1 model=deepseek-flash maxOutputTokens=128 totalTokens=%d%n",
                response.usage().totalTokens());
    }

    private static String localApiKey() {
        String value = firstNonBlank(System.getProperty("LEARNHUB_DEEPSEEK_API_KEY"),
                System.getProperty("DEEPSEEK_API_KEY"), System.getenv("LEARNHUB_DEEPSEEK_API_KEY"),
                System.getenv("DEEPSEEK_API_KEY"));
        if (value != null) return value;
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path env = candidate.resolve(".env");
            if (!Files.isRegularFile(env)) continue;
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(env, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return unquote(firstNonBlank(properties.getProperty("LEARNHUB_DEEPSEEK_API_KEY"),
                        properties.getProperty("DEEPSEEK_API_KEY")));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return first == last && (first == '\'' || first == '"') ? value.substring(1, value.length() - 1) : value;
    }
}
