package com.learnhub.assistant.model;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "learnhub.ai")
public class AiProperties {
    private static final Set<String> SUPPORTED_MODELS = Set.of("deepseek-flash", "deepseek-v4-pro");

    private String mode = "DISABLED";
    private String apiKey = "";
    private String model = "deepseek-flash";
    private int maxOutputTokens = 1_024;
    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private int maxInputBytes = 131_072;
    private int maxResponseBytes = 262_144;
    private int transportThreads = 4;

    public Mode resolvedMode() {
        String normalized = mode == null ? Mode.DISABLED.name() : mode.trim().toUpperCase(Locale.ROOT);
        try {
            return Mode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException(
                    "Unsupported learnhub.ai.mode: " + mode + ". Expected DISABLED, FAKE, or DEEPSEEK");
        }
    }

    public void validateForDeepSeek() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DeepSeek API key is required when learnhub.ai.mode=DEEPSEEK");
        }
        if (!SUPPORTED_MODELS.contains(model)) {
            throw new IllegalArgumentException("Unsupported DeepSeek model: " + model);
        }
        if (maxOutputTokens < 1 || maxOutputTokens > ChatRequest.MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("learnhub.ai.max-output-tokens must be between 1 and 2048");
        }
        requirePositive(requestTimeout, "learnhub.ai.request-timeout");
        requirePositive(connectTimeout, "learnhub.ai.connect-timeout");
        if (maxInputBytes < 1_024) throw new IllegalArgumentException("learnhub.ai.max-input-bytes is too small");
        if (maxResponseBytes < 1_024) throw new IllegalArgumentException("learnhub.ai.max-response-bytes is too small");
        if (transportThreads < 1 || transportThreads > 32) {
            throw new IllegalArgumentException("learnhub.ai.transport-threads must be between 1 and 32");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getTransportThreads() { return transportThreads; }
    public void setTransportThreads(int transportThreads) { this.transportThreads = transportThreads; }

    public enum Mode {
        DISABLED,
        FAKE,
        DEEPSEEK
    }
}
