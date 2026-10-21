package com.learnhub.assistant.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.model.transport.BoundedJdkHttpClientBuilder;
import com.learnhub.assistant.chat.model.transport.SharedJdkHttpTransport;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.runtime.*;
import com.learnhub.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 实际普通 LangChain4j 模型与本机 HTTP Mock；没有供应商请求。 */
@Timeout(10)
class PreferenceExtractionServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final PreferenceExtractionAttemptStore attempts = mock(PreferenceExtractionAttemptStore.class);
    final PreferenceMemoryService preferences = mock(PreferenceMemoryService.class);
    final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    final AssistantRunGuard.Run guard = mock(AssistantRunGuard.Run.class);
    final AiProperties properties = new AiProperties();
    final AssistantLimits limits = new AssistantLimits();
    final List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    ExecutorService workers;
    SharedJdkHttpTransport shared;
    String output = "{\"items\":[]}";
    boolean includeUsage = true;
    int httpStatus = 200;
    Runnable onRequest = () -> { };

    @BeforeEach void setup() throws Exception {
        limits.setModelWindowTokens(24000); // 本地 HTTP Mock 的显式窗口。
        properties.setMode("DEEPSEEK"); properties.setApiKey("mock-only"); properties.setModel("deepseek-flash");
        properties.setMaxOutputTokens(512); properties.setRequestTimeout(Duration.ofSeconds(3));
        when(guard.remaining()).thenReturn(Duration.ofSeconds(5));
        when(guard.summary()).thenReturn(new AssistantRunSummary("run", 1, 0, 0, 1000, 10, 5, 15, true, "RUNNING", 1));
        when(attempts.claim(anyLong(), any(), anyString(), anyString(), anyInt())).thenReturn(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newFixedThreadPool(2); server.setExecutor(workers);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                onRequest.run();
                Map<String,Object> response = new LinkedHashMap<>(Map.of("id", "mock", "model", "deepseek-flash",
                        "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", output), "finish_reason", "stop"))));
                if (includeUsage) response.put("usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                byte[] body = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(httpStatus, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        shared = new SharedJdkHttpTransport(Duration.ofSeconds(1), 2, 2, 8);
    }
    @AfterEach void close() throws Exception {
        try { shared.close(); } finally { server.stop(0); workers.shutdownNow(); assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
    PreferenceExtractionService service(boolean enabled, boolean memory) {
        return new PreferenceExtractionService(attempts, preferences, persistence, properties, limits, enabled, memory);
    }
    ChatPersistenceService.Run run(String text) {
        return new ChatPersistenceService.Run("run", "conversation", 7, "request", "RUNNING", "lease", text);
    }
    PreferenceExtractionService.Result extract(PreferenceExtractionService service, String text) {
        try (var scope = new AssistantIoScope(Duration.ofSeconds(5)); var transport = new BoundedJdkHttpClientBuilder(shared)) {
            scope.attach();
            return service.extract(7, run(text), guard, scope::remaining, transport,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        }
    }
    @Test void bothFlagsAreRequiredAndOrdinaryQuestionsAreNotUploaded() {
        assertThat(extract(service(false, true), "我喜欢先看例子").attempted()).isFalse();
        assertThat(extract(service(true, false), "我喜欢先看例子").attempted()).isFalse();
        assertThat(extract(service(true, true), "什么是缓存").attempted()).isFalse();
        assertThat(extract(service(true, true), "我喜欢密码123456").attempted()).isFalse();
        assertThat(extract(service(true, true), "我喜欢" + "例".repeat(200)).attempted()).isFalse();
        assertThat(requests).isEmpty(); verifyNoInteractions(attempts, preferences, persistence);
    }

    @Test void explicitWindowLimitRejectsSerializedExtractionBeforeHttpAndKeepsItsReservation() {
        limits.setMaxOutputTokens(512);
        limits.setModelWindowTokens(800);
        limits.setContextSafetyMarginTokens(256);
        assertThat(limits.isValidConfiguration()).isTrue();
        assertThatThrownBy(() -> extract(service(true, true), "我喜欢先看例子"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("AI_MODEL_CONTEXT_WINDOW_EXCEEDED"));
        assertThat(requests).isEmpty();
        verify(guard).reserveModel(anyInt(), eq(128));
        verify(attempts).reserved(eq(7L), eq("run"), anyInt());
        verify(guard, never()).recordUsage(anyLong(), anyLong(), anyLong());
    }
    @Test void literalPreferenceIsChargedAndWrittenWithOwnedConversationOnly() throws Exception {
        output = "{\"items\":[{\"category\":\"STYLE\",\"text\":\"先看例子\"}]}";
        when(preferences.create(7, "STYLE", "先看例子", "conversation")).thenReturn(
                new PreferenceMemoryService.PreferenceView("preference", 1, "STYLE", "先看例子", "ACTIVE", "conversation", 1L, java.time.LocalDateTime.now()));
        assertThat(extract(service(true, true), "我喜欢先看例子")).isEqualTo(new PreferenceExtractionService.Result(true, true, 10, 5, 15));
        assertThat(requests).hasSize(1);
        var request = json.readTree(requests.get(0));
        assertThat(request.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(request.path("messages")).hasSize(2);
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("我喜欢先看例子");
        assertThat(requests.get(0)).doesNotContain("tool_call_id", "evidence", "conversation", "mock-only");
        var order = inOrder(guard, attempts, persistence, preferences);
        order.verify(attempts).claim(eq(7L), any(), eq("deepseek-flash"), anyString(), anyInt());
        order.verify(guard).reserveModel(anyInt(), eq(128));
        order.verify(attempts).reserved(eq(7L), eq("run"), anyInt());
        order.verify(persistence).recordUsage(eq(7L), eq("run"), eq("lease"), contains("RESERVED"));
        order.verify(guard).recordUsage(10, 5, 15);
        order.verify(attempts).usage(7, "run", 10, 5, 15);
        order.verify(persistence).recordUsage(eq(7L), eq("run"), eq("lease"), contains("ACTUAL"));
        order.verify(preferences).create(7, "STYLE", "先看例子", "conversation");
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"items\":[{\"category\":\"STYLE\",\"text\":\"模型捏造\"}]}",
            "{\"items\":[{\"category\":\"FACT\",\"text\":\"先看例子\"}]}",
            "{\"items\":[{\"category\":\"STYLE\",\"text\":\"先看例子\",\"userId\":8}]}",
            "{\"items\":[],\"items\":[]}", "{\"items\":[],\"extra\":true}", "{\"items\":[", "{\"items\":[]}{}"})
    void invalidOutputStillChargesKnownUsageWithoutCreatingPreference(String invalid) {
        output = invalid;
        assertThat(extract(service(true, true), "我喜欢先看例子").usageKnown()).isTrue();
        verify(guard).recordUsage(10, 5, 15); verifyNoInteractions(preferences);
        verify(attempts).finish(7, "run", "REJECTED", "EXTRACTION_FAILED", null);
    }
    @Test void missingUsageIsUnknownAndKeepsReservation() {
        includeUsage = false;
        assertThat(extract(service(true, true), "我每晚只有二十分钟").usageKnown()).isFalse();
        verify(guard).reserveModel(anyInt(), eq(128)); verify(guard, never()).recordUsage(anyLong(), anyLong(), anyLong());
        verify(attempts).finish(7, "run", "UNKNOWN", "EXTRACTION_FAILED", null); verifyNoInteractions(preferences);
    }
    @Test void emptyMatchIsChargedWithoutSavingPreference() {
        assertThat(extract(service(true, true), "我喜欢先看例子").usageKnown()).isTrue();
        verify(attempts).finish(7, "run", "NO_MATCH", null, null);
        verify(guard).recordUsage(10, 5, 15); verifyNoInteractions(preferences);
    }
    @Test void failedUsageAuditDoesNotCreatePreferenceEvenWhenSupplierUsageIsKnown() {
        output = "{\"items\":[{\"category\":\"STYLE\",\"text\":\"先看例子\"}]}";
        doThrow(new IllegalStateException()).when(attempts).usage(7, "run", 10, 5, 15);
        assertThat(extract(service(true, true), "我喜欢先看例子").usageKnown()).isTrue();
        verify(guard).recordUsage(10, 5, 15); verifyNoInteractions(preferences);
    }
    @Test void supplierFailureDoesNotRetryOrCallMem0() {
        httpStatus = 503;
        assertThat(extract(service(true, true), "我喜欢先看例子").usageKnown()).isFalse();
        assertThat(requests).hasSize(1); verifyNoInteractions(preferences);
    }
    @Test void dailyBudgetDenialDoesNotSendHttp() {
        doThrow(new BusinessException("ASSISTANT_DAILY_LIMIT", "额度已满")).when(guard).reserveModel(anyInt(), anyInt());
        var result = extract(service(true, true), "我喜欢先看例子");
        assertThat(result).isEqualTo(new PreferenceExtractionService.Result(true, true, 0, 0, 0));
        assertThat(requests).isEmpty(); verify(attempts).finish(7, "run", "BUDGET_REJECTED", "EXTRACTION_BUDGET", null);
    }
    @Test void alreadyClaimedRunAndTooSmallOutputBudgetDoNotSendHttp() {
        when(attempts.claim(anyLong(), any(), anyString(), anyString(), anyInt())).thenReturn(false);
        assertThat(extract(service(true, true), "我喜欢先看例子").attempted()).isFalse();
        properties.setMaxOutputTokens(64);
        assertThat(extract(service(true, true), "我喜欢先看例子").attempted()).isFalse();
        assertThat(requests).isEmpty(); verify(guard, never()).reserveModel(anyInt(), anyInt());
    }
    @Test void auditWriteFailureBeforeHttpKeepsReservationAndDoesNotCreatePreference() {
        doThrow(new IllegalStateException()).when(persistence).recordUsage(anyLong(), anyString(), anyString(), anyString());
        assertThat(extract(service(true, true), "我喜欢先看例子").usageKnown()).isFalse();
        assertThat(requests).isEmpty(); verify(guard).reserveModel(anyInt(), anyInt()); verifyNoInteractions(preferences);
    }
    @Test void cancellationWhileHttpIsInFlightPropagatesAndKeepsUnknownAudit() {
        try (var scope = new AssistantIoScope(Duration.ofSeconds(5)); var transport = new BoundedJdkHttpClientBuilder(shared)) {
            scope.attach(); onRequest = scope::cancel;
            assertThatThrownBy(() -> service(true, true).extract(7, run("我喜欢先看例子"), guard, scope::remaining,
                    transport, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
            verifyNoInteractions(preferences);
        }
    }
}
