package com.learnhub.assistant.knowledge.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiliconFlowRerankerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "sk-" + "r".repeat(60);
    private static final int MAX_BODY = 128 * 1024;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Function<JsonNode, Reply>> responder = new AtomicReference<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean blockResponses = new AtomicBoolean();
    private volatile CountDownLatch blockedStarted = new CountDownLatch(2);
    private volatile CountDownLatch releaseResponses = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService workers;
    private URI endpoint;

    @BeforeEach
    void startMock() throws Exception {
        responder.set(request -> validReply(request.path("documents").size()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "siliconflow-rerank-mock");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/v1/rerank", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank");
    }

    @AfterEach
    void stopMock() throws Exception {
        releaseResponses.countDown();
        if (server != null) server.stop(0);
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sendsExactProtocolAndSortsScoresWithStableIndexTieBreak() {
        responder.set(ignored -> jsonReply(Map.of("results", List.of(
                result(2, 1.25), result(0, -0.5), result(1, 1.25)))));
        try (var reranker = client()) {
            assertThat(reranker.candidateLimit()).isEqualTo(20);
            assertThat(reranker.rerank("退款规则", List.of("文档甲", "文档乙", "文档丙")))
                    .containsExactly(new KnowledgeReranker.Ranked(1, 1.25),
                            new KnowledgeReranker.Ranked(2, 1.25),
                            new KnowledgeReranker.Ranked(0, -0.5));
        }

        assertThat(requests).hasValue(1);
        assertThat(methods).containsExactly("POST");
        assertThat(authorizations).containsExactly("Bearer " + API_KEY);
        assertThat(contentTypes).containsExactly("application/json");
        JsonNode request = bodies.get(0);
        assertThat(toSet(request.fieldNames())).containsExactlyInAnyOrder(
                "model", "query", "documents", "top_n", "return_documents",
                "max_chunks_per_doc", "overlap_tokens");
        assertThat(request.path("model").textValue()).isEqualTo("BAAI/bge-reranker-v2-m3");
        assertThat(request.path("query").textValue()).isEqualTo("退款规则");
        assertThat(texts(request.path("documents"))).containsExactly("文档甲", "文档乙", "文档丙");
        assertThat(request.path("top_n").intValue()).isEqualTo(3);
        assertThat(request.path("return_documents").booleanValue()).isFalse();
        assertThat(request.path("max_chunks_per_doc").intValue()).isOne();
        assertThat(request.path("overlap_tokens").intValue()).isZero();
        assertThat(request.has("instruction")).isFalse();
    }

    @Test
    void validatesConfigurationAndInputsBeforeNetworkOrResourceUse() {
        SiliconFlowReranker.validateConfiguration(
                URI.create("https://api.siliconflow.cn/v1/rerank"), API_KEY, Duration.ofSeconds(5), 20);
        assertThatThrownBy(() -> SiliconFlowReranker.validateConfiguration(
                URI.create("https://example.invalid/v1/rerank"), API_KEY, Duration.ofSeconds(5), 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiliconFlowReranker.validateConfiguration(
                URI.create("http://192.0.2.1/v1/rerank"), API_KEY, Duration.ofSeconds(5), 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiliconFlowReranker.validateConfiguration(
                endpoint, API_KEY, Duration.ofMillis(99), 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiliconFlowReranker.validateConfiguration(
                endpoint, API_KEY, Duration.ofSeconds(5), 51)).isInstanceOf(IllegalArgumentException.class);

        try (var reranker = client()) {
            assertUnavailable(() -> reranker.rerank(" ", List.of("甲", "乙")));
            assertUnavailable(() -> reranker.rerank("问".repeat(501), List.of("甲", "乙")));
            assertUnavailable(() -> reranker.rerank("问题", List.of("甲")));
            assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "\uD800")));
            assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "乙".repeat(601))));
        }
        List<String> requestTooLarge = new ArrayList<>();
        String escapedControlText = "x" + String.valueOf((char) 1).repeat(599);
        for (int index = 0; index < 50; index++) requestTooLarge.add(escapedControlText);
        try (var reranker = new SiliconFlowReranker(endpoint, API_KEY, Duration.ofSeconds(2), 50)) {
            assertUnavailable(() -> reranker.rerank("问题", requestTooLarge));
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void providerErrorsAreNotRetriedAndDoNotLeakCredentialsOrBody() {
        for (int status : List.of(400, 401, 429)) {
            responder.set(ignored -> new Reply(status, "application/json",
                    "{\"message\":\"provider-marker\"}".getBytes(StandardCharsets.UTF_8), Duration.ZERO, false));
            try (var reranker = client()) {
                assertThatThrownBy(() -> reranker.rerank("问题", List.of("甲", "乙")))
                        .isInstanceOfSatisfying(BusinessException.class, failure -> {
                            assertThat(failure.getCode()).isEqualTo("RERANK_UNAVAILABLE");
                            assertThat(failure.getMessage()).doesNotContain(API_KEY, "provider-marker", "问题");
                        });
            }
        }
        assertThat(requests).hasValue(3);
    }

    @Test
    void rejectsInvalidJsonIndexesAndScores() {
        List<Reply> invalid = List.of(
                jsonReply(Map.of("results", List.of(result(0, 1.0)))),
                jsonReply(Map.of("results", List.of(result(0, 1.0), result(0, 0.5)))),
                jsonReply(Map.of("results", List.of(result(0, 1.0), result(2, 0.5)))),
                rawReply("{\"results\":[{\"index\":0.5,\"relevance_score\":1},{\"index\":1,\"relevance_score\":0}]}"),
                rawReply("{\"results\":[{\"index\":0,\"relevance_score\":1e9999},{\"index\":1,\"relevance_score\":0}]}"),
                rawReply("{\"results\":[],\"results\":[]}"),
                rawReply("{\"results\":[]} trailing")
        );
        for (Reply reply : invalid) {
            responder.set(ignored -> reply);
            try (var reranker = client()) {
                assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "乙")));
            }
        }
        assertThat(requests).hasValue(invalid.size());
    }

    @Test
    void rejectsOversizedResponseAndTimesOutAcrossDelayedBody() {
        byte[] oversized = new byte[MAX_BODY + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        responder.set(ignored -> new Reply(200, "application/json", oversized, Duration.ZERO, false));
        try (var reranker = client()) {
            assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "乙")));
        }

        responder.set(ignored -> new Reply(200, "application/json", validReply(2).body(),
                Duration.ofMillis(350), true));
        try (var reranker = new SiliconFlowReranker(endpoint, API_KEY, Duration.ofMillis(100), 20)) {
            assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "乙")));
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    void preservesCancellationAndScopeDeadlineWithPreCancelledScopeSendingNothing() throws Exception {
        try (var scope = new AssistantIoScope(Duration.ofSeconds(2)); var reranker = client()) {
            scope.attach();
            scope.cancel();
            assertCode(() -> reranker.rerank("问题", List.of("甲", "乙")), "ASSISTANT_CANCELLED");
        }
        assertThat(requests).hasValue(0);

        blockResponses.set(true);
        blockedStarted = new CountDownLatch(1);
        releaseResponses = new CountDownLatch(1);
        try (var scope = new AssistantIoScope(Duration.ofSeconds(2)); var reranker = client()) {
            scope.attach();
            Thread canceller = new Thread(() -> {
                try {
                    if (blockedStarted.await(1, TimeUnit.SECONDS)) scope.cancel();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "siliconflow-rerank-canceller");
            canceller.start();
            assertCode(() -> reranker.rerank("问题", List.of("甲", "乙")), "ASSISTANT_CANCELLED");
            canceller.join(2000);
            assertThat(canceller.isAlive()).isFalse();
        }
        releaseResponses.countDown();
        blockResponses.set(false);

        responder.set(ignored -> new Reply(200, "application/json", validReply(2).body(),
                Duration.ofSeconds(1), false));
        try (var scope = new AssistantIoScope(Duration.ofMillis(120)); var reranker = client()) {
            scope.attach();
            assertCode(() -> reranker.rerank("问题", List.of("甲", "乙")), "ASSISTANT_DEADLINE_EXCEEDED");
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    void closeCancelsInFlightRequestAndIsIdempotent() throws Exception {
        blockResponses.set(true);
        blockedStarted = new CountDownLatch(1);
        var reranker = client();
        CompletableFuture<List<KnowledgeReranker.Ranked>> call = CompletableFuture.supplyAsync(
                () -> reranker.rerank("问题", List.of("甲", "乙")));
        assertThat(blockedStarted.await(2, TimeUnit.SECONDS)).isTrue();
        reranker.close();
        reranker.close();
        assertThatThrownBy(call::join).hasRootCauseInstanceOf(BusinessException.class);
        releaseResponses.countDown();
        assertUnavailable(() -> reranker.rerank("问题", List.of("甲", "乙")));
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsThirdConcurrentRequestImmediatelyWithoutSendingHttp() throws Exception {
        blockResponses.set(true);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (var reranker = client()) {
            var first = callers.submit(() -> reranker.rerank("问题一", List.of("甲", "乙")));
            var second = callers.submit(() -> reranker.rerank("问题二", List.of("丙", "丁")));
            assertThat(blockedStarted.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertUnavailable(() -> reranker.rerank("问题三", List.of("戊", "己")));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
            assertThat(requests).hasValue(2);

            releaseResponses.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).hasSize(2);
            assertThat(second.get(2, TimeUnit.SECONDS)).hasSize(2);
        } finally {
            releaseResponses.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SiliconFlowReranker client() {
        return new SiliconFlowReranker(endpoint, API_KEY, Duration.ofSeconds(2), 20);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertCode(action, "RERANK_UNAVAILABLE");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        methods.add(exchange.getRequestMethod());
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        bodies.add(request);
        Reply reply = responder.get().apply(request);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        try {
            if (blockResponses.get()) {
                blockedStarted.countDown();
                releaseResponses.await(3, TimeUnit.SECONDS);
            }
            if (reply.headersFirst()) {
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                sleep(reply.delay());
            } else {
                sleep(reply.delay());
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
            }
            exchange.getResponseBody().write(reply.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // 超时、scope取消和close测试会主动断开本次隔离连接。
        } finally {
            exchange.close();
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) return;
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static Set<String> toSet(java.util.Iterator<String> values) {
        Set<String> result = new java.util.HashSet<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.textValue()));
        return values;
    }

    private static Reply validReply(int count) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < count; index++) results.add(result(index, count - index));
        return jsonReply(Map.of("id", "mock", "results", results, "meta", Map.of("tokens", 12)));
    }

    private static Map<String, Object> result(int index, double score) {
        return Map.of("index", index, "relevance_score", score, "document", Map.of("text", "untrusted"));
    }

    private static Reply jsonReply(Object body) {
        try { return new Reply(200, "application/json", JSON.writeValueAsBytes(body), Duration.ZERO, false); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Reply rawReply(String body) {
        return new Reply(200, "application/json", body.getBytes(StandardCharsets.UTF_8), Duration.ZERO, false);
    }

    private record Reply(int status, String contentType, byte[] body, Duration delay, boolean headersFirst) { }
}
