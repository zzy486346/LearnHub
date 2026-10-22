package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.model.transport.BoundedJdkHttpClientBuilder;
import com.learnhub.assistant.chat.model.transport.SharedJdkHttpTransport;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.assistant.runtime.AssistantRunSummary;
import com.learnhub.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 使用本地同步 HTTP Mock 验证摘要调用的预算、usage 和取消边界。 */
@Timeout(10)
class SemanticSummaryCallTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties properties = new AiProperties();
    private final AssistantLimits limits = new AssistantLimits();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService workers;
    private SharedJdkHttpTransport shared;
    private volatile int status = 200;
    private volatile boolean includeUsage = true;

    @BeforeEach void setUp() throws Exception {
        limits.setModelWindowTokens(24_000);
        limits.setContextSafetyMarginTokens(256);
        properties.setMode("DEEPSEEK");
        properties.setApiKey("mock-only");
        properties.setModel("deepseek-flash");
        properties.setRequestTimeout(Duration.ofSeconds(3));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newFixedThreadPool(2);
        server.setExecutor(workers);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                Map<String, Object> response = new LinkedHashMap<>(Map.of(
                        "id", "summary-mock", "model", "deepseek-flash",
                        "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", validSummary()),
                                "finish_reason", "stop"))));
                if (includeUsage) response.put("usage",
                        Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18));
                byte[] body = mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        shared = new SharedJdkHttpTransport(Duration.ofSeconds(1), 2, 2, 8);
    }

    @AfterEach void close() throws Exception {
        try {
            shared.close();
        } finally {
            server.stop(0);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void insufficientCallsOrTokensSkipsBeforeReservationAndHttp() {
        var callGuard = guard(3, 1000);
        var tokenGuard = guard(0, 23_500);

        assertThat(invoke(new SemanticSummaryCall(mapper, properties, limits), callGuard, () -> { }))
                .extracting(SemanticSummaryCall.Result::reason).isEqualTo("SUMMARY_BUDGET");
        assertThat(invoke(new SemanticSummaryCall(mapper, properties, limits), tokenGuard, () -> { }))
                .extracting(SemanticSummaryCall.Result::reason).isEqualTo("SUMMARY_BUDGET");

        assertThat(requests).hasValue(0);
        verify(callGuard, never()).reserveModel(anyInt(), anyInt());
        verify(tokenGuard, never()).reserveModel(anyInt(), anyInt());
    }

    @Test void successRecordsUsageBetweenReservedAndActualAuditWrites() {
        var guard = guard(0, 0);
        Runnable saveUsage = mock(Runnable.class);

        var result = invoke(new SemanticSummaryCall(mapper, properties, limits), guard, saveUsage);

        assertThat(result).isEqualTo(new SemanticSummaryCall.Result(validSummary(), true, true,
                11, 7, 18, "SUMMARY_READY"));
        assertThat(requests).hasValue(1);
        var order = inOrder(guard, saveUsage);
        order.verify(guard).tryReserveSummary(anyInt(), org.mockito.ArgumentMatchers.eq(128), anyInt(), anyInt());
        order.verify(saveUsage).run();
        order.verify(guard).recordUsage(11, 7, 18);
        order.verify(saveUsage).run();
    }

    @Test void summaryEstimateOutsideExplicitWindowSkipsBeforeDailyReservation() {
        limits.setModelWindowTokens(2000);
        var guard = guard(0, 0);
        assertThat(invoke(new SemanticSummaryCall(mapper, properties, limits), guard, () -> { }).reason()).isEqualTo("SUMMARY_INPUT");
        assertThat(requests).hasValue(0);
        verify(guard, never()).tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test void httpFailureAndUnknownUsageKeepReservationAndNeverRetry() {
        for (boolean supplierFailure : new boolean[] {true, false}) {
            requests.set(0);
            status = supplierFailure ? 503 : 200;
            includeUsage = supplierFailure;
            var guard = guard(0, 0);
            var call = new SemanticSummaryCall(mapper, properties, limits);

            assertThat(invoke(call, guard, () -> { })).isEqualTo(
                    new SemanticSummaryCall.Result("", true, false, 0, 0, 0, "SUMMARY_FAILED"));
            assertThat(requests).hasValue(1);
            verify(guard).tryReserveSummary(anyInt(), org.mockito.ArgumentMatchers.eq(128), anyInt(), anyInt());
            verify(guard, never()).recordUsage(anyLong(), anyLong(), anyLong());

            assertThat(invoke(call, guard, () -> { }).reason()).isEqualTo("SUMMARY_ALREADY_ATTEMPTED");
            assertThat(requests).hasValue(1);
        }
    }

    @Test void oneInstanceAttemptsAtMostOnceAfterSuccess() {
        var guard = guard(0, 0);
        var call = new SemanticSummaryCall(mapper, properties, limits);

        assertThat(invoke(call, guard, () -> { }).reason()).isEqualTo("SUMMARY_READY");
        assertThat(invoke(call, guard, () -> { }).reason()).isEqualTo("SUMMARY_ALREADY_ATTEMPTED");
        assertThat(requests).hasValue(1);
    }

    @Test void answerTimeAndAtomicDailyCapacityAreRequiredBeforeAnySummaryHttp() {
        var shortTime = guard(0, 0);
        when(shortTime.remaining()).thenReturn(Duration.ofMillis(1400));
        assertThat(invoke(new SemanticSummaryCall(mapper, properties, limits), shortTime, () -> { }).reason())
                .isEqualTo("SUMMARY_DEADLINE");
        verify(shortTime, never()).tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt());
        var lastDailyCall = guard(0, 0);
        when(lastDailyCall.tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(false);
        assertThat(invoke(new SemanticSummaryCall(mapper, properties, limits), lastDailyCall, () -> { }).reason())
                .isEqualTo("SUMMARY_BUDGET");
        assertThat(requests).hasValue(0);
    }

    @Test void knownUsageAuditFailureIsNeverTurnedIntoUnknownUsageOrSoftDegradation() {
        var guard = guard(0, 0);
        var writes = new AtomicInteger();
        Runnable saveUsage = () -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("usage audit unavailable");
        };
        assertThatThrownBy(() -> invoke(new SemanticSummaryCall(mapper, properties, limits), guard, saveUsage))
                .isInstanceOf(IllegalStateException.class).hasMessage("usage audit unavailable");
        assertThat(requests).hasValue(1);
        verify(guard).recordUsage(11, 7, 18);
    }

    @Test void cancellationBusinessExceptionPropagatesAfterReservationWithoutHttpOrRefund() {
        var guard = guard(0, 0);
        Runnable cancelled = () -> { throw new BusinessException("ASSISTANT_CANCELLED", "已取消"); };

        assertThatThrownBy(() -> invoke(new SemanticSummaryCall(mapper, properties, limits), guard,
                cancelled, () -> { }))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
        assertThat(requests).hasValue(0);
        verify(guard).tryReserveSummary(anyInt(), org.mockito.ArgumentMatchers.eq(128), anyInt(), anyInt());
        verify(guard, never()).recordUsage(anyLong(), anyLong(), anyLong());
    }

    private SemanticSummaryCall.Result invoke(SemanticSummaryCall call, AssistantRunGuard.Run guard,
            Runnable saveUsage) {
        return invoke(call, guard, null, saveUsage);
    }

    private SemanticSummaryCall.Result invoke(SemanticSummaryCall call, AssistantRunGuard.Run guard,
            Runnable checkpointOverride, Runnable saveUsage) {
        try (var scope = new AssistantIoScope(Duration.ofSeconds(5));
                var transport = new BoundedJdkHttpClientBuilder(shared)) {
            scope.attach();
            Runnable checkpoint = checkpointOverride == null ? scope::remaining : checkpointOverride;
            return call.summarize(7, storedRun(), guard, checkpoint, transport, endpoint(),
                    "{\"previousSummary\":null,\"rounds\":[]}", 1000, 128, saveUsage);
        }
    }

    private AssistantRunGuard.Run guard(int modelCalls, long reservedTokens) {
        AssistantRunGuard.Run guard = mock(AssistantRunGuard.Run.class);
        when(guard.remaining()).thenReturn(Duration.ofSeconds(5));
        when(guard.tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(guard.summary()).thenReturn(new AssistantRunSummary("run", modelCalls, 0, 0,
                reservedTokens, 0, 0, 0, false, "RUNNING", 0));
        return guard;
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private static ChatPersistenceService.Run storedRun() {
        return new ChatPersistenceService.Run("run", "conversation", 7, "request",
                "RUNNING", "lease", "问题");
    }

    private static String validSummary() {
        return """
                {"schemaVersion":1,"taskState":{"goal":[],"topic":[],"constraints":[],"decisions":[],
                "corrections":[],"openQuestions":[],"nextStep":[]},"conversationSummary":[],"unresolvedReferences":[]}
                """.strip();
    }
}
