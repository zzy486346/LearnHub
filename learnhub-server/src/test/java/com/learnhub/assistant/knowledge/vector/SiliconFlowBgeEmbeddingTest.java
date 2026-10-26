package com.learnhub.assistant.knowledge.vector;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiliconFlowBgeEmbeddingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "sk-" + "x".repeat(60);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Function<JsonNode, Reply>> responder = new AtomicReference<>();
    private final List<JsonNode> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final CountDownLatch requestStarted = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService workers;
    private URI endpoint;

    @BeforeEach
    void startMock() throws Exception {
        responder.set(request -> validReply(inputs(request).size(), false));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "siliconflow-embedding-mock");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/v1/embeddings", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
    }

    @AfterEach
    void stopMock() throws Exception {
        if (server != null) server.stop(0);
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sendsDocumentBatchAndRestoresProviderIndexOrderBeforeNormalizing() {
        responder.set(request -> validReply(inputs(request).size(), true));
        try (var embedding = client()) {
            var result = embedding.embedDocuments(List.of("退款后无法观看课程", "使用鸡蛋制作蛋糕"));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).vector()).hasSize(1024);
            assertThat(result.get(0).vector()[0]).isEqualTo(1.0f);
            assertThat(result.get(0).vector()[1]).isZero();
            assertThat(result.get(1).vector()[0]).isZero();
            assertThat(result.get(1).vector()[1]).isEqualTo(1.0f);
            assertThat(embedding.embeddingCount()).isEqualTo(2);
            assertThat(embedding.dimension()).isEqualTo(1024);
            assertThat(embedding.modelId()).isEqualTo(SiliconFlowBgeEmbedding.MODEL_ID);
            assertThat(embedding.tableName()).isEqualTo(SiliconFlowBgeEmbedding.TABLE);
        }

        assertThat(requests).hasValue(1);
        assertThat(authorizations).containsExactly("Bearer " + API_KEY);
        assertThat(methods).containsExactly("POST");
        assertThat(contentTypes).containsExactly("application/json");
        JsonNode request = requestBodies.get(0);
        assertThat(request.path("model").textValue()).isEqualTo("BAAI/bge-large-zh-v1.5");
        assertThat(request.path("encoding_format").textValue()).isEqualTo("float");
        assertThat(request.has("dimensions")).isFalse();
        assertThat(inputs(request)).containsExactly("退款后无法观看课程", "使用鸡蛋制作蛋糕");
    }

    @Test
    void prefixesOnlyQueriesAndWorksWithoutAssistantIoScope() {
        try (var embedding = client()) {
            assertThat(embedding.embedQuery("退款规则").vector()).hasSize(1024);
            assertThat(embedding.embeddingCount()).isOne();
        }
        assertThat(inputs(requestBodies.get(0))).containsExactly(BgeChineseEmbedding.QUERY_PREFIX + "退款规则");
    }

    @Test
    void usesExactLowercasingTokenizerAndSplitsUnicodeWithoutLosingContent() {
        try (var embedding = client()) {
            String chinese = "课".repeat(600);
            var chineseParts = embedding.splitDocument(chinese);
            assertThat(chineseParts).hasSize(2);
            assertThat(chineseParts.get(0)).hasSize(478);
            assertThat(chineseParts.get(1)).hasSize(152);
            assertThat(chineseParts.get(0).substring(chineseParts.get(0).length() - 30))
                    .isEqualTo(chineseParts.get(1).substring(0, 30));
            assertThat(chineseParts).allSatisfy(part -> assertThat(embedding.countTokens(part)).isLessThanOrEqualTo(480));
            assertThat(embedding.countTokens(chineseParts.get(0))).isEqualTo(480);

            String upper = "LEARNHUB Course CACHE Redis";
            assertThat(embedding.countTokens(upper)).isEqualTo(embedding.countTokens(upper.toLowerCase(java.util.Locale.ROOT)));
            String english = (upper + " ").repeat(21);
            assertThat(english.length()).isLessThanOrEqualTo(600);
            assertThat(embedding.splitDocument(english))
                    .allSatisfy(part -> assertThat(embedding.countTokens(part)).isLessThanOrEqualTo(480));

            String emoji = "课程😀".repeat(150);
            assertThat(emoji.length()).isEqualTo(600);
            assertThat(embedding.splitDocument(emoji)).allSatisfy(part -> {
                assertThat(embedding.countTokens(part)).isLessThanOrEqualTo(480);
                assertThat(part.codePoints()).doesNotContain(0xD800, 0xDFFF);
            });

            String distinct = java.util.stream.IntStream.range(0x4E00, 0x4E00 + 600)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            var distinctParts = embedding.splitDocument(distinct);
            assertThat(distinctParts.size()).isGreaterThan(1);
            String reconstructed = distinctParts.get(0);
            for (int i = 1; i < distinctParts.size(); i++) {
                int offset = distinct.indexOf(distinctParts.get(i));
                int covered = reconstructed.length();
                assertThat(offset).isBetween(0, covered);
                reconstructed += distinctParts.get(i).substring(covered - offset);
            }
            assertThat(reconstructed).isEqualTo(distinct);
            assertThat(distinctParts).allSatisfy(part -> {
                assertThat(embedding.countTokens(part)).isLessThanOrEqualTo(480);
                assertThat(part.codePoints()).doesNotContain(0xD800, 0xDFFF);
            });
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void rejectsNonOfficialHttpsAndNonLoopbackHttpEndpointsBeforeNetwork() {
        assertThatThrownBy(() -> new SiliconFlowBgeEmbedding(
                URI.create("https://example.invalid/v1/embeddings"), API_KEY, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiliconFlowBgeEmbedding(
                URI.create("http://192.0.2.1/v1/embeddings"), API_KEY, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        try (var accepted = new SiliconFlowBgeEmbedding(
                URI.create("https://api.siliconflow.cn/v1/embeddings"), API_KEY, Duration.ofSeconds(2))) {
            assertThat(accepted.modelId()).isEqualTo("sf-bge-large-zh-v15-t480-o32-v1");
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void cancelledScopeRejectsBeforeStartingHttp() {
        try (var scope = new AssistantIoScope(Duration.ofSeconds(2)); var embedding = client()) {
            scope.attach();
            scope.cancel();
            assertThatThrownBy(() -> embedding.embedQuery("课程"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void rejectsInvalidInputBeforeHttpAndCloseIsIdempotent() {
        var embedding = client();
        assertThatThrownBy(() -> embedding.embedDocuments(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedDocuments(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedDocuments(Collections.nCopies(33, "课程")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedDocuments(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedDocuments(List.of("课".repeat(481))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedDocuments(List.of("\uD800")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.embedQuery("课".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.splitDocument("课".repeat(601))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding.splitDocument(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
        assertThat(embedding.embeddingCount()).isZero();

        embedding.close();
        embedding.close();
        assertThatThrownBy(() -> embedding.embedQuery("课程")).isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void providerErrorsAreNotRetriedAndDoNotLeakSecretOrBody() {
        for (int status : List.of(400, 429)) {
            responder.set(ignored -> new Reply(status, "application/json", "{\"message\":\"provider-marker\"}"
                    .getBytes(StandardCharsets.UTF_8), Duration.ZERO, false));
            try (var embedding = client()) {
                assertUnavailable(() -> embedding.embedQuery("课程"), embedding);
            }
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    void rejectsMalformedIndexesDimensionsValuesAndUsageWithoutCountingBatch() throws Exception {
        List<InvalidCase> invalid = List.of(
                new InvalidCase(List.of("课程"), request ->
                        jsonReply(response(List.of(data(1, vector(0, 1))), usage()))),
                new InvalidCase(List.of("课程一", "课程二"), request -> jsonReply(response(
                        List.of(data(0, vector(0, 1)), data(0, vector(1, 1))), usage()))),
                new InvalidCase(List.of("课程"), request ->
                        jsonReply(response(List.of(data(0, vectorLength(1023))), usage()))),
                new InvalidCase(List.of("课程"), request ->
                        jsonReply(response(List.of(data(0, zeroVector())), usage()))),
                new InvalidCase(List.of("课程"), request ->
                        jsonReply(Map.of("object", "list", "model", SiliconFlowBgeEmbedding.MODEL,
                                "data", List.of(data(0, vector(0, 1))))))
        );
        for (InvalidCase testCase : invalid) {
            responder.set(testCase.response());
            try (var embedding = client()) {
                assertUnavailable(() -> embedding.embedDocuments(testCase.inputs()), embedding);
            }
        }

        responder.set(ignored -> rawReply(nonFiniteResponse()));
        try (var embedding = client()) {
            assertUnavailable(() -> embedding.embedDocuments(List.of("课程")), embedding);
        }
        assertThat(requests).hasValue(invalid.size() + 1);
    }

    @Test
    void rejectsOversizedResponseAndTimesOutWhileWaitingForBody() {
        byte[] oversized = new byte[MAX_RESPONSE_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        responder.set(ignored -> new Reply(200, "application/json", oversized, Duration.ZERO, false));
        try (var embedding = client()) {
            assertUnavailable(() -> embedding.embedQuery("课程"), embedding);
        }

        responder.set(ignored -> new Reply(200, "application/json", validReply(1, false).body(),
                Duration.ofMillis(250), true));
        try (var embedding = new SiliconFlowBgeEmbedding(endpoint, API_KEY, Duration.ofMillis(30))) {
            assertUnavailable(() -> embedding.embedQuery("课程"), embedding);
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    void preservesAssistantCancellationAndDeadlineCodes() throws Exception {
        responder.set(ignored -> new Reply(200, "application/json", validReply(1, false).body(),
                Duration.ofSeconds(2), false));
        try (var scope = new AssistantIoScope(Duration.ofSeconds(3)); var embedding = client()) {
            scope.attach();
            Thread canceller = new Thread(() -> {
                try {
                    if (requestStarted.await(1, TimeUnit.SECONDS)) scope.cancel();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "siliconflow-canceller");
            canceller.start();
            assertThatThrownBy(() -> embedding.embedQuery("课程"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
            canceller.join(2000);
            assertThat(canceller.isAlive()).isFalse();
        }

        responder.set(ignored -> new Reply(200, "application/json", validReply(1, false).body(),
                Duration.ofMillis(250), false));
        try (var scope = new AssistantIoScope(Duration.ofMillis(40)); var embedding = client()) {
            scope.attach();
            assertThatThrownBy(() -> embedding.embedQuery("课程"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_DEADLINE_EXCEEDED"));
        }
    }

    private SiliconFlowBgeEmbedding client() {
        return new SiliconFlowBgeEmbedding(endpoint, API_KEY, Duration.ofSeconds(2));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                   SiliconFlowBgeEmbedding embedding) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo("VECTOR_EMBEDDING_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain(API_KEY, "provider-marker");
        });
        assertThat(embedding.embeddingCount()).isZero();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        requestStarted.countDown();
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        methods.add(exchange.getRequestMethod());
        contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode request = JSON.readTree(requestBytes);
        requestBodies.add(request);
        Reply reply = responder.get().apply(request);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        try {
            if (reply.headersFirst()) {
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                sleep(reply.delay());
                exchange.getResponseBody().write(reply.body());
            } else {
                sleep(reply.delay());
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                exchange.getResponseBody().write(reply.body());
            }
        } catch (IOException disconnected) {
            // 超时和取消测试会主动断开本次隔离连接。
        } finally {
            exchange.close();
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) return;
        try { Thread.sleep(delay.toMillis()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static List<String> inputs(JsonNode request) {
        List<String> values = new ArrayList<>();
        request.path("input").forEach(item -> values.add(item.textValue()));
        return values;
    }

    private static Reply validReply(int size, boolean reverse) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int index = 0; index < size; index++) data.add(data(index, vector(index, index + 2)));
        if (reverse) Collections.reverse(data);
        return jsonReply(response(data, usage()));
    }

    private static Map<String, Object> response(List<Map<String, Object>> data, Map<String, Object> usage) {
        return Map.of("object", "list", "model", SiliconFlowBgeEmbedding.MODEL,
                "data", data, "usage", usage);
    }

    private static Map<String, Object> usage() {
        return Map.of("prompt_tokens", 8, "completion_tokens", 0, "total_tokens", 8);
    }

    private static Map<String, Object> data(int index, List<Double> vector) {
        return Map.of("object", "embedding", "index", index, "embedding", vector);
    }

    private static List<Double> vector(int coordinate, double value) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
        vector.set(coordinate, value);
        return vector;
    }

    private static List<Double> vectorLength(int length) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(length, 0.0));
        vector.set(0, 1.0);
        return vector;
    }

    private static List<Double> zeroVector() {
        return new ArrayList<>(Collections.nCopies(1024, 0.0));
    }

    private static Reply jsonReply(Object body) {
        try { return new Reply(200, "application/json", JSON.writeValueAsBytes(body), Duration.ZERO, false); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Reply rawReply(String body) {
        return new Reply(200, "application/json", body.getBytes(StandardCharsets.UTF_8), Duration.ZERO, false);
    }

    private static String nonFiniteResponse() {
        try {
            String valid = JSON.writeValueAsString(response(List.of(data(0, vector(0, 1))), usage()));
            return valid.replaceFirst("1\\.0", "1e9999");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Reply(int status, String contentType, byte[] body, Duration delay, boolean headersFirst) { }
    private record InvalidCase(List<String> inputs, Function<JsonNode, Reply> response) { }
}
