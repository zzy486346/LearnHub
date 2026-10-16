package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssistantLimitsTest {
    @Test
    void defaultsAreBoundedForPaidModelCalls() {
        AssistantLimits limits = new AssistantLimits();

        assertThat(limits.isValidConfiguration()).isTrue();
        assertThat(limits.getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(limits.getMaxModelCalls()).isEqualTo(4);
        assertThat(limits.getMaxToolDecisionRounds()).isEqualTo(3);
        assertThat(limits.getMaxToolCalls()).isEqualTo(4);
        assertThat(limits.getMaxRunTokens()).isEqualTo(24_000);
        assertThat(limits.getMaxOutputTokens()).isEqualTo(1_200);
        assertThat(limits.getGlobalConcurrency()).isEqualTo(4);
        assertThat(limits.getRequestsPerMinute()).isEqualTo(6);
        assertThat(limits.getDailyModelCalls()).isEqualTo(20);
        assertThat(limits.getDailyReservedTokens()).isEqualTo(120_000);
        assertThat(limits.getMaxInputBytes()).isEqualTo(16_000);
    }

    @Test
    void rejectsUnsafeOrContradictoryValues() {
        AssistantLimits limits = new AssistantLimits();
        limits.setTimeout(Duration.ofMinutes(6));
        assertThat(limits.isValidConfiguration()).isFalse();

        limits = new AssistantLimits();
        limits.setMaxOutputTokens(limits.getMaxRunTokens() + 1);
        assertThat(limits.isValidConfiguration()).isFalse();

        limits = new AssistantLimits();
        limits.setDailyReservedTokens(limits.getMaxRunTokens() - 1);
        assertThat(limits.isValidConfiguration()).isFalse();
    }
}
