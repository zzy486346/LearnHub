package com.learnhub.assistant.runtime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "learnhub.assistant")
public class AssistantLimits {
    private Duration timeout = Duration.ofSeconds(45);
    private Duration leaseGrace = Duration.ofSeconds(30);
    private int maxModelCalls = 4;
    private int maxToolDecisionRounds = 3;
    private int maxToolCalls = 4;
    private int maxRunTokens = 24_000;
    private int maxOutputTokens = 1_200;
    private int globalConcurrency = 4;
    private int requestsPerMinute = 6;
    private int dailyModelCalls = 20;
    private int dailyReservedTokens = 120_000;
    private int maxInputBytes = 16_000;

    @AssertTrue(message = "learnhub.assistant 配置超出安全范围")
    public boolean isValidConfiguration() {
        return between(timeout, Duration.ofSeconds(1), Duration.ofMinutes(5))
                && between(leaseGrace, Duration.ofSeconds(5), Duration.ofMinutes(2))
                && between(maxModelCalls, 1, 20)
                && between(maxToolDecisionRounds, 1, 10)
                && between(maxToolCalls, 1, 20)
                && between(maxRunTokens, 1, 1_000_000)
                && between(maxOutputTokens, 1, 2_048)
                && maxOutputTokens <= maxRunTokens
                && between(globalConcurrency, 1, 100)
                && between(requestsPerMinute, 1, 120)
                && between(dailyModelCalls, 1, 10_000)
                && between(dailyReservedTokens, 1, 100_000_000)
                && maxRunTokens <= dailyReservedTokens
                && between(maxInputBytes, 1, 1_000_000);
    }

    private static boolean between(@NotNull Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private static boolean between(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getLeaseGrace() { return leaseGrace; }
    public void setLeaseGrace(Duration leaseGrace) { this.leaseGrace = leaseGrace; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
    public int getMaxToolDecisionRounds() { return maxToolDecisionRounds; }
    public void setMaxToolDecisionRounds(int maxToolDecisionRounds) { this.maxToolDecisionRounds = maxToolDecisionRounds; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public int getMaxRunTokens() { return maxRunTokens; }
    public void setMaxRunTokens(int maxRunTokens) { this.maxRunTokens = maxRunTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getGlobalConcurrency() { return globalConcurrency; }
    public void setGlobalConcurrency(int globalConcurrency) { this.globalConcurrency = globalConcurrency; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public int getDailyModelCalls() { return dailyModelCalls; }
    public void setDailyModelCalls(int dailyModelCalls) { this.dailyModelCalls = dailyModelCalls; }
    public int getDailyReservedTokens() { return dailyReservedTokens; }
    public void setDailyReservedTokens(int dailyReservedTokens) { this.dailyReservedTokens = dailyReservedTokens; }
    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
}
