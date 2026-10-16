package com.learnhub.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learnhub.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatModelClientTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatModelConfiguration.class);

    @Test
    void defaultsToDisabledAndReportsThatModelIsUnavailable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModelClient.class);
            ChatModelClient client = context.getBean(ChatModelClient.class);

            assertThatThrownBy(() -> client.generate("hello"))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("AI_DISABLED");
                        assertThat(exception).hasMessage("AI 模型功能尚未启用");
                    });
        });
    }

    @Test
    void fakeModeReturnsBoundedResponseWithoutEchoingPrompt() {
        String privatePrompt = "private lesson body: account=secret";

        contextRunner.withPropertyValues("learnhub.ai.mode=FAKE").run(context -> {
            ChatModelClient client = context.getBean(ChatModelClient.class);
            String response = client.generate(privatePrompt);

            assertThat(response)
                    .isEqualTo(FakeChatModelClient.RESPONSE)
                    .doesNotContain(privatePrompt)
                    .hasSizeLessThanOrEqualTo(100);
        });
    }

    @Test
    void fakeModeReturnsExplicitStructuredRefusalWithoutPretendingToUseTokens() {
        contextRunner.withPropertyValues("learnhub.ai.mode=FAKE").run(context -> {
            ChatModelClient client = context.getBean(ChatModelClient.class);
            ChatResponse response = client.generate(new ChatRequest(
                    List.of(new ChatMessage("user", "请回答", null, List.of())),
                    List.of(), 128, Duration.ofSeconds(1), true));

            assertThat(response.text()).isEqualTo(FakeChatModelClient.JSON_RESPONSE);
            assertThat(response.modelMode()).isEqualTo("FAKE");
            assertThat(response.usage()).isEqualTo(new Usage(0, 0, 0));
            assertThat(response.toolCalls()).isEmpty();
        });
    }

    @Test
    void fakeModeRejectsBlankAndOversizedPrompts() {
        contextRunner.withPropertyValues("learnhub.ai.mode=FAKE").run(context -> {
            ChatModelClient client = context.getBean(ChatModelClient.class);

            assertThatThrownBy(() -> client.generate(" "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("prompt must not be blank");
            assertThatThrownBy(() -> client.generate("x".repeat(FakeChatModelClient.MAX_PROMPT_LENGTH + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed");
        });
    }

    @Test
    void rejectsUnknownModeDuringStartup() {
        contextRunner.withPropertyValues("learnhub.ai.mode=REMOTE").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("Unsupported learnhub.ai.mode: REMOTE");
        });
    }

    @Test
    void deepSeekModeRequiresApiKeyBeforeCreatingTransport() {
        contextRunner.withPropertyValues("learnhub.ai.mode=DEEPSEEK").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("API key is required");
        });
    }
}
