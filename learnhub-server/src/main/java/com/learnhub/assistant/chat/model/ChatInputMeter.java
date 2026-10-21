package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 每次运行独有的输入占用账；不持有 guard，不修改成本预留，不保存正文。 */
public final class ChatInputMeter {
    private static final int PROTOCOL_BYTES = 2048;
    private final ObjectMapper mapper;
    private final String scope;
    private final int modelWindowTokens;
    private final int safetyMarginTokens;
    private Pending pending;
    private Measurement last;
    private Anchor anchor;

    public ChatInputMeter(ObjectMapper mapper, String scope) {
        this(mapper, scope, 0, 0);
    }

    public ChatInputMeter(ObjectMapper mapper, String scope, int modelWindowTokens, int safetyMarginTokens) {
        this.mapper = Objects.requireNonNull(mapper);
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("missing context scope");
        if (modelWindowTokens < 0 || safetyMarginTokens < 0) throw new IllegalArgumentException("invalid model window");
        this.scope = scope;
        this.modelWindowTokens = modelWindowTokens;
        this.safetyMarginTokens = safetyMarginTokens;
    }

    /** 沿用旧规划估算和协议余量。SDK 完整正文在 beforeSend 再测量。 */
    int estimateBytes(List<ChatMessage> messages, Object tools) {
        long total = PROTOCOL_BYTES + utf8(json(messages.stream().map(Object::toString).toList()));
        if (tools != null) total += utf8(json(tools));
        return saturated(total);
    }

    int roundBytes(String user, String assistant, String dependencies) {
        return saturated((long) estimateBytes(List.of(UserMessage.from(user), AiMessage.from(assistant)), null)
                - PROTOCOL_BYTES + utf8(json(dependencies)) + 256L);
    }

    public void expectRequest(int reservation, int inputLimit, int outputReservation) {
        if (modelWindowTokens == 0)
            throw rejected("AI_MODEL_CONTEXT_WINDOW_UNCONFIGURED", "请先显式配置已核验的模型窗口 Token 数");
        if (reservation < 0 || inputLimit < 1 || outputReservation < 1 || pending != null)
            throw new IllegalStateException("invalid or unconsumed context plan");
        pending = new Pending(reservation, inputLimit, outputReservation);
    }

    /** 在 SPI delegate.execute 之前调用；一个已扣账 plan 只能授权一次发送。 */
    public Measurement beforeSend(String body) {
        Pending planned = pending;
        pending = null;
        last = null;
        if (planned == null) throw rejected("ASSISTANT_MODEL_REQUEST_UNEXPECTED", "模型请求缺少独立预算预留");
        if (body == null) throw rejected("ASSISTANT_CONTEXT_INVALID", "模型请求正文无效");
        int bytes = utf8(body);
        if (bytes > planned.inputLimit()) throw rejected("AI_MODEL_INPUT_TOO_LARGE", "模型序列化输入超过 UTF-8 字节预算");
        if (bytes > planned.reservation())
            throw rejected("ASSISTANT_INPUT_ESTIMATE_EXCEEDED", "模型序列化输入超过本次保守预留，已停止发送");
        try {
            var root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (root == null || !root.isObject() || !root.path("messages").isArray())
                throw rejected("ASSISTANT_CONTEXT_INVALID", "模型请求消息结构无效");
            String fingerprint = hash(scope + "\n" + body);
            int count = root.path("messages").size();
            // 完全相同请求是可证明的前缀特例；其余情况不猜测增量，整次重新估算。
            boolean matched = anchor != null && anchor.fingerprint().equals(fingerprint) && anchor.messageCount() == count;
            if (!matched) anchor = null;
            last = new Measurement(bytes, matched ? anchor.inputTokens() : bytes,
                    planned.reservation(), planned.outputReservation(), fingerprint, count,
                    matched ? EstimateSource.EXACT_REQUEST_USAGE : EstimateSource.UTF8_BYTES);
            if ((long) last.estimatedInputTokens() + last.outputReservation()
                    + safetyMarginTokens > modelWindowTokens) {
                last = null;
                throw rejected("AI_MODEL_CONTEXT_WINDOW_EXCEEDED", "模型输入估计与输出预留超过已配置窗口");
            }
            return last;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception invalid) { throw rejected("ASSISTANT_CONTEXT_INVALID", "模型请求无法测量"); }
    }

    public void recordUsage(int inputTokens) {
        if (last == null || inputTokens < 0) throw new IllegalStateException("usage without measured request");
        anchor = new Anchor(last.fingerprint(), last.messageCount(), inputTokens);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw rejected("ASSISTANT_CONTEXT_INVALID", "上下文无法序列化"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static int saturated(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
    private static BusinessException rejected(String code, String message) { return new BusinessException(code, message); }

    public enum EstimateSource { UTF8_BYTES, EXACT_REQUEST_USAGE }
    public record Measurement(int requestBytes, int estimatedInputTokens, int conservativeInputReservation,
                       int outputReservation, String fingerprint, int messageCount, EstimateSource estimateSource) { }
    private record Pending(int reservation, int inputLimit, int outputReservation) { }
    private record Anchor(String fingerprint, int messageCount, int inputTokens) { }
}
