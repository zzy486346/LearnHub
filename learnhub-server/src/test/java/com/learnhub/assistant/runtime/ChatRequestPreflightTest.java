package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.model.transport.BoundedJdkHttpClientBuilder;
import com.learnhub.assistant.chat.model.transport.SharedJdkHttpTransport;
import com.learnhub.assistant.chat.model.ChatInputMeter;
import com.learnhub.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatRequestPreflightTest {
    private static final String BODY = "{\"model\":\"mock\",\"messages\":[{\"role\":\"user\",\"content\":\"中文\"}]}";

    @Test void exactUsageCalibrationCannotReduceAccumulatedRunOrDailyReservations() {
        var store = mock(AssistantRunStore.class);
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt())).thenReturn(AssistantRunStore.AcquireResult.ACQUIRED);
        when(store.reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong())).thenReturn(AssistantRunStore.ReserveResult.RESERVED);
        var meter = new ChatInputMeter(new ObjectMapper(), "fixture", 24000, 256);
        try (var run = new AssistantRunGuard(store, new AssistantLimits()).open(7L, "usage-anchor", () -> { })) {
            for (int round = 0; round < 2; round++) {
                meter.expectRequest(5000, 10000, 512);
                run.reserveModel(5000, 512);
                var measured = meter.beforeSend(BODY);
                assertThat(measured.estimateSource()).isEqualTo(round == 0
                        ? ChatInputMeter.EstimateSource.UTF8_BYTES : ChatInputMeter.EstimateSource.EXACT_REQUEST_USAGE);
                meter.recordUsage(7);
                run.recordUsage(7, 1, 8);
            }
            assertThat(run.summary().reservedTokens()).isEqualTo(11024);
            assertThat(run.summary().actualTotalTokens()).isEqualTo(16);
            assertThat(run.summary().usageComplete()).isTrue();
            verify(store, times(2)).reserveDaily(anyString(), anyString(), anyInt(), anyInt(), eq(5512), anyLong());
        }
    }

    @Test void syncAndStreamingHardFailuresReachNoHttpEndpointAndDoNotRefundReservations() throws Exception {
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { received.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        var store = mock(AssistantRunStore.class);
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt())).thenReturn(AssistantRunStore.AcquireResult.ACQUIRED);
        when(store.reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong())).thenReturn(AssistantRunStore.ReserveResult.RESERVED);
        var guard = new AssistantRunGuard(store, new AssistantLimits());
        try (var scope = new AssistantIoScope(Duration.ofSeconds(5));
             var shared = new SharedJdkHttpTransport(Duration.ofSeconds(1), 2, 1, 4);
             var run = guard.open(7L, "preflight", () -> { })) {
            scope.attach();
            for (boolean streaming : new boolean[] {false, true}) {
                for (boolean localLimit : new boolean[] {false, true}) {
                    var meter = new ChatInputMeter(new ObjectMapper(), "fixture", 24000, 256);
                    int reserved = localLimit ? 5000 : 1;
                    run.reserveModel(reserved, 512);
                    meter.expectRequest(reserved, localLimit ? 1 : 10000, 512);
                    try (var builder = new BoundedJdkHttpClientBuilder(shared).beforeRequest(meter::beforeSend)) {
                        var client = builder.build();
                        var request = HttpRequest.builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                                .method(HttpMethod.POST).body(BODY).build();
                        assertThatThrownBy(() -> {
                            if (streaming) client.execute(request, mock(ServerSentEventParser.class), mock(ServerSentEventListener.class));
                            else client.execute(request);
                        }).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(
                                localLimit ? "AI_MODEL_INPUT_TOO_LARGE" : "ASSISTANT_INPUT_ESTIMATE_EXCEEDED"));
                    }
                }
            }
            assertThat(run.summary().modelCalls()).isEqualTo(4);
            assertThat(run.summary().reservedTokens()).isEqualTo(12050);
            assertThat(run.summary().usageComplete()).isFalse();
            assertThat(received).hasValue(0);
            verify(store, times(4)).reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong());
        } finally { server.stop(0); }
    }

    @Test void acceptedSdkBodyMeasurementEqualsWhatTheMockActuallyReceives() throws Exception {
        var received = new AtomicReference<String>();
        var measured = new AtomicReference<ChatInputMeter.Measurement>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write("{}".getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        try (var scope = new AssistantIoScope(Duration.ofSeconds(5));
             var shared = new SharedJdkHttpTransport(Duration.ofSeconds(1), 2, 1, 4);
             var builder = new BoundedJdkHttpClientBuilder(shared)) {
            scope.attach();
            var meter = new ChatInputMeter(new ObjectMapper(), "fixture", 24000, 256);
            meter.expectRequest(5000, 10000, 512);
            var client = builder.beforeRequest(body -> measured.set(meter.beforeSend(body))).build();
            client.execute(HttpRequest.builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .method(HttpMethod.POST).body(BODY).build());
            assertThat(received.get()).isEqualTo(BODY);
            assertThat(measured.get().requestBytes()).isEqualTo(received.get().getBytes(StandardCharsets.UTF_8).length);
        } finally { server.stop(0); }
    }
}
