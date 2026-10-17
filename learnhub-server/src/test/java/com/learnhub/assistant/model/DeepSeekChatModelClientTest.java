package com.learnhub.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeepSeekChatModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<DeepSeekChatModelClient> clients = new ArrayList<>();
    private final AtomicReference<ExchangeHandler> handler = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> handler.get().handle(exchange));
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(DeepSeekChatModelClient::close);
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsBoundedNonThinkingJsonToolRequestAndParsesUsage() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        handler.set(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            captured.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, success("{\"insufficientEvidence\":true,\"answerSegments\":[]}"));
        });
        AiProperties properties = properties();
        properties.setMaxOutputTokens(128);
        DeepSeekChatModelClient client = client(properties);
        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", "search_courses", "parameters", Map.of("type", "object"))));

        ChatResponse response = client.generate(new ChatRequest(
                List.of(new ChatMessage("user", "请用 json 回答", null, List.of())),
                tools, 512, Duration.ofSeconds(2), true));

        assertThat(response.text()).contains("insufficientEvidence");
        assertThat(response.usage()).isEqualTo(new Usage(11, 7, 18));
        assertThat(response.modelMode()).isEqualTo("DEEPSEEK");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        JsonNode request = captured.get();
        assertThat(request.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(request.path("tools").size()).isEqualTo(1);
    }

    @Test
    void parsesToolCallsAndRejectsMalformedArguments() {
        DeepSeekChatModelClient client = client(properties());
        handler.set(exchange -> respond(exchange, 200, """
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"search_courses","arguments":"{\\"keyword\\":\\"Java\\"}"}}
                ]}}],"usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}
                """));
        ChatResponse response = client.generate(request(Duration.ofSeconds(2)));
        assertThat(response.toolCalls())
                .containsExactly(new ToolCall("call-1", "search_courses", "{\"keyword\":\"Java\"}"));
        assertThat(response.finishReason()).isEqualTo("tool_calls");

        handler.set(exchange -> respond(exchange, 200, """
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                  {"id":"call-2","type":"function","function":{"name":"search_courses","arguments":"not-json"}}
                ]}}],"usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}
                """));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_MALFORMED_RESPONSE");
    }

    @Test void retainsKnownUsageWhenProviderTruncatesOutput() {
        handler.set(exchange -> respond(exchange, 200, responseWith("length", "partial")));
        assertThatThrownBy(() -> client(properties()).generate(request(Duration.ofSeconds(2))))
                .isInstanceOfSatisfying(ModelClientException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo("AI_MODEL_TRUNCATED");
                    assertThat(failure.usage()).isEqualTo(new Usage(1, 1, 2));
                });
    }

    @Test
    void mapsProviderAndContractFailuresWithoutLeakingProviderBody() {
        DeepSeekChatModelClient client = client(properties());

        handler.set(exchange -> respond(exchange, 401, "secret provider detail"));
        assertThatThrownBy(() -> client.generate(request(Duration.ofSeconds(2))))
                .isInstanceOfSatisfying(ModelClientException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("AI_MODEL_AUTHENTICATION_FAILED");
                    assertThat(exception).hasMessageNotContaining("secret provider detail");
                });

        handler.set(exchange -> respond(exchange, 429, "retry later"));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_RATE_LIMITED");

        handler.set(exchange -> respond(exchange, 200, "not-json"));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_MALFORMED_RESPONSE");

        handler.set(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}"));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_MISSING_USAGE");

        handler.set(exchange -> respond(exchange, 200, responseWith("stop", "")));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_EMPTY_RESPONSE");

        handler.set(exchange -> respond(exchange, 200, responseWith("length", "partial")));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_TRUNCATED");

        handler.set(exchange -> respond(exchange, 200, responseWith("new_reason", "text")));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_UNKNOWN_FINISH_REASON");
    }

    @Test
    void rejectsOversizedInputAndResponseLocally() {
        AiProperties properties = properties();
        properties.setMaxInputBytes(1_024);
        properties.setMaxResponseBytes(1_024);
        DeepSeekChatModelClient client = client(properties);

        ChatRequest oversized = new ChatRequest(
                List.of(new ChatMessage("user", "汉".repeat(600), null, List.of())),
                List.of(), 64, Duration.ofSeconds(2), false);
        assertCode(() -> client.generate(oversized), "AI_MODEL_INPUT_TOO_LARGE");

        handler.set(exchange -> respond(exchange, 200, "x".repeat(2_000)));
        assertCode(() -> client.generate(request(Duration.ofSeconds(2))), "AI_MODEL_RESPONSE_TOO_LARGE");
    }

    @Test
    void deadlineCoversResponseBodyStallAndClosesTheLiveBody() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        handler.set(exchange -> stallBody(exchange, bodyStarted, release, disconnected));
        AiProperties properties = properties();
        properties.setRequestTimeout(Duration.ofMillis(200));
        DeepSeekChatModelClient client = client(properties);

        long started = System.nanoTime();
        assertCode(() -> client.generate(request(Duration.ofSeconds(5))), "AI_MODEL_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(bodyStarted.await(1, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void interruptCancelsResponseBodyAndPreservesInterruptFlag() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        handler.set(exchange -> stallBody(exchange, bodyStarted, release, disconnected));
        DeepSeekChatModelClient client = client(properties());
        AtomicReference<String> code = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                client.generate(request(Duration.ofSeconds(10)));
            } catch (ModelClientException exception) {
                code.set(exception.getCode());
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);
        release.countDown();

        assertThat(caller.isAlive()).isFalse();
        assertThat(code.get()).isEqualTo("AI_MODEL_CANCELLED");
        assertThat(interrupted).isTrue();
        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private DeepSeekChatModelClient client(AiProperties properties) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        DeepSeekChatModelClient client = new DeepSeekChatModelClient(mapper, properties, uri);
        clients.add(client);
        return client;
    }

    private static AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setMode("DEEPSEEK");
        properties.setApiKey("test-key");
        properties.setRequestTimeout(Duration.ofSeconds(3));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setTransportThreads(2);
        return properties;
    }

    private static ChatRequest request(Duration timeout) {
        return new ChatRequest(
                List.of(new ChatMessage("user", "hello", null, List.of())),
                List.of(), 64, timeout, false);
    }

    private static String success(String text) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + quote(text) + "}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";
    }

    private static String responseWith(String finishReason, String text) {
        return "{\"choices\":[{\"finish_reason\":" + quote(finishReason)
                + ",\"message\":{\"content\":" + quote(text)
                + "}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void stallBody(
            HttpExchange exchange,
            CountDownLatch bodyStarted,
            CountDownLatch release,
            CountDownLatch disconnected) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        bodyStarted.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) return;
            byte[] block = new byte[64 * 1024];
            while (true) {
                exchange.getResponseBody().write(block);
                exchange.getResponseBody().flush();
            }
        } catch (IOException exception) {
            disconnected.countDown();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void assertCode(ThrowingCall call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelClientException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
