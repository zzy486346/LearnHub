package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChatInputMeterTest {
    private final ChatInputMeter meter = new ChatInputMeter(new ObjectMapper(), "owner:course:conversation:model", 24000, 256);
    private static final String BODY = "{\"model\":\"mock\",\"messages\":[{\"role\":\"user\",\"content\":\"中文\\n问题\"}],\"tools\":[]}";

    @Test void measuresTheEntireSerializedRequestAndNeverLowersCostReservation() {
        meter.expectRequest(5000, 10000, 512);
        var measured = meter.beforeSend(BODY);
        assertThat(measured.requestBytes()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
        assertThat(measured.conservativeInputReservation()).isEqualTo(5000);
        assertThat(measured.estimatedInputTokens()).isEqualTo(measured.requestBytes());
        assertThat(measured.outputReservation()).isEqualTo(512);
        assertThat(measured.messageCount()).isOne();
        assertThat(measured.estimateSource()).isEqualTo(ChatInputMeter.EstimateSource.UTF8_BYTES);
        meter.recordUsage(7);
        meter.expectRequest(5000, 10000, 512);
        var anchored = meter.beforeSend(BODY);
        assertThat(anchored.estimatedInputTokens()).isEqualTo(7);
        assertThat(anchored.conservativeInputReservation()).isEqualTo(5000);
        assertThat(anchored.estimateSource()).isEqualTo(ChatInputMeter.EstimateSource.EXACT_REQUEST_USAGE);
    }

    @Test void anyRequestOrScopeChangeInvalidatesTheAnchor() {
        for (String changed : new String[] {
                BODY.replace("问题", "新问题"), BODY.replace("\"tools\":[]", "\"tools\":[{\"name\":\"schema-v2\"}]"),
                BODY.replace("\"user\"", "\"system\""), BODY.replace("mock", "other-model"),
                BODY.replace("中文", "evidence-v2"), BODY.replace("中文", "preference-v2"),
                BODY.replace("中文", "summary-v2"), BODY.replace("}]", "},{\"role\":\"user\",\"content\":\"追加\"}]")
        }) {
            meter.expectRequest(5000, 10000, 512);
            meter.beforeSend(BODY);
            meter.recordUsage(1);
            meter.expectRequest(5000, 10000, 512);
            assertThat(meter.beforeSend(changed).estimateSource()).isEqualTo(ChatInputMeter.EstimateSource.UTF8_BYTES);
        }
        var another = new ChatInputMeter(new ObjectMapper(), "other-owner:course:conversation:model", 24000, 256);
        another.expectRequest(5000, 10000, 512);
        assertThat(another.beforeSend(BODY).estimateSource()).isEqualTo(ChatInputMeter.EstimateSource.UTF8_BYTES);
    }

    @Test void rejectsBeforeSendWhenActualBodyExceedsEitherHardLimit() {
        meter.expectRequest(5000, 1, 512);
        assertCode(() -> meter.beforeSend(BODY), "AI_MODEL_INPUT_TOO_LARGE");
        meter.expectRequest(1, 10000, 512);
        assertCode(() -> meter.beforeSend(BODY), "ASSISTANT_INPUT_ESTIMATE_EXCEEDED");
    }

    @Test void aPlanCannotAuthorizeTwoHttpRequestsAndFailedUsageCreatesNoAnchor() {
        meter.expectRequest(5000, 10000, 512);
        meter.beforeSend(BODY);
        assertCode(() -> meter.beforeSend(BODY), "ASSISTANT_MODEL_REQUEST_UNEXPECTED");
        meter.expectRequest(5000, 10000, 512);
        assertThat(meter.beforeSend(BODY).estimateSource()).isEqualTo(ChatInputMeter.EstimateSource.UTF8_BYTES);
    }

    @Test void configuredWindowIncludesOutputAndSafetyAndIsSeparateFromCost() {
        int bytes = BODY.getBytes(StandardCharsets.UTF_8).length;
        var exact = new ChatInputMeter(new ObjectMapper(), "scope", bytes + 512 + 256, 256);
        exact.expectRequest(5000, 10000, 512);
        assertThat(exact.beforeSend(BODY).conservativeInputReservation()).isEqualTo(5000);
        var tooSmall = new ChatInputMeter(new ObjectMapper(), "scope", bytes + 512 + 255, 256);
        tooSmall.expectRequest(5000, 10000, 512);
        assertCode(() -> tooSmall.beforeSend(BODY), "AI_MODEL_CONTEXT_WINDOW_EXCEEDED");
    }

    @Test void windowConfigurationCannotBeNegativeOrSmallerThanOutputAndMargin() {
        var limits = new com.learnhub.assistant.runtime.AssistantLimits();
        assertThat(limits.isValidConfiguration()).isTrue();
        limits.setModelWindowTokens(-1);
        assertThat(limits.isValidConfiguration()).isFalse();
        limits.setModelWindowTokens(limits.getMaxOutputTokens() + limits.getContextSafetyMarginTokens());
        assertThat(limits.isValidConfiguration()).isFalse();
        limits.setModelWindowTokens(24000);
        assertThat(limits.isValidConfiguration()).isTrue();
        limits.setContextSafetyMarginTokens(-1);
        assertThat(limits.isValidConfiguration()).isFalse();
    }

    @Test void missingVerifiedWindowCannotAuthorizeAnyRequest() {
        var unconfigured = new ChatInputMeter(new ObjectMapper(), "scope");
        assertCode(() -> unconfigured.expectRequest(5000, 10000, 512), "AI_MODEL_CONTEXT_WINDOW_UNCONFIGURED");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
