package com.learnhub.assistant.memory.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.vector.BgeChineseEmbedding;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MemoryEmbeddingBridgeTest {
    private static final String TOKEN = "test-only-memory-bridge-credential";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static MemoryEmbeddingBridge bridge;

    @BeforeAll static void start() throws Exception {
        bridge = new MemoryEmbeddingBridge("127.0.0.1", 0, TOKEN, new BgeChineseEmbedding(), Duration.ofSeconds(10));
        bridge.start();
    }

    @AfterAll static void stop() { if (bridge != null) bridge.close(); }

    @Test void servesAuthenticatedHealthAndRejectsWrongOrMissingCredentials() throws Exception {
        assertThat(get("/health", TOKEN).statusCode()).isEqualTo(200);
        var wrong = get("/health", "wrong-private-key");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).doesNotContain("wrong-private-key", TOKEN);
        assertThat(get("/internal/stats", null).statusCode()).isEqualTo(401);
        assertThat(get("/health?token=x", TOKEN).statusCode()).isEqualTo(400);
    }

    @Test void encodesChineseAndEmojiAsSymmetric512DimensionVectorsWithUsageAndCounters() throws Exception {
        var before = JSON.readTree(get("/internal/stats", TOKEN).body());
        var response = post(Map.of("model", MemoryEmbeddingBridge.MODEL,
                "input", List.of("我喜欢通过 Java 例子学习😀", "请先提供简短结论"), "dimensions", 512,
                "encoding_format", "float"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        assertThat(result.path("model").asText()).isEqualTo(MemoryEmbeddingBridge.MODEL);
        assertThat(result.path("object").asText()).isEqualTo("list");
        assertThat(result.path("data").size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            var item = result.path("data").get(i);
            assertThat(item.path("index").asInt()).isEqualTo(i);
            assertThat(item.path("embedding").size()).isEqualTo(512);
            double norm = 0;
            for (var value : item.path("embedding")) {
                assertThat(Double.isFinite(value.asDouble())).isTrue();
                norm += value.asDouble() * value.asDouble();
            }
            assertThat(norm).isBetween(0.998, 1.002);
        }
        assertThat(result.path("usage").path("prompt_tokens").asInt()).isPositive();
        assertThat(result.path("usage").path("total_tokens")).isEqualTo(result.path("usage").path("prompt_tokens"));
        var after = JSON.readTree(get("/internal/stats", TOKEN).body());
        assertThat(after.path("embedding_requests").asLong()).isEqualTo(before.path("embedding_requests").asLong() + 1);
        assertThat(after.path("embedded_texts").asLong()).isEqualTo(before.path("embedded_texts").asLong() + 2);
        assertThat(after.toString()).doesNotContain("Java", TOKEN);
    }

    @Test void acceptsSingleStringAndMatchesDocumentEmbeddingWithoutQueryPrefix() throws Exception {
        String text = "我喜欢课程配合代码示例";
        var result = JSON.readTree(post(Map.of("model", MemoryEmbeddingBridge.MODEL, "input", text)).body());
        try (var local = new BgeChineseEmbedding()) {
            var expected = local.embedDocuments(List.of(text)).get(0).vector();
            for (int i = 0; i < expected.length; i++)
                assertThat(result.path("data").get(0).path("embedding").get(i).floatValue()).isEqualTo(expected[i]);
            assertThat(result.path("usage").path("prompt_tokens").asInt()).isEqualTo(local.countTokens(text));
        }
    }

    @Test void rejectsModelDimensionsEncodingAndUnknownFields() throws Exception {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("model", "remote-provider", "input", "课程"),
                Map.of("model", MemoryEmbeddingBridge.MODEL, "input", "课程", "dimensions", 1536),
                Map.of("model", MemoryEmbeddingBridge.MODEL, "input", "课程", "dimensions", 512.0),
                Map.of("model", MemoryEmbeddingBridge.MODEL, "input", "课程", "encoding_format", "base64"),
                Map.of("model", MemoryEmbeddingBridge.MODEL, "input", "课程", "user", "private-owner"))) {
            var response = post(body);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("private-owner", "remote-provider", "课程");
        }
    }

    @Test void rejectsBatchLimitsTokensAndInvalidUnicodeBeforeEncoding() throws Exception {
        var before = JSON.readTree(get("/internal/stats", TOKEN).body());
        for (Object input : List.of(List.of(), java.util.Collections.nCopies(9, "课"), List.of(1), "课".repeat(600),
                "x".repeat(601), " ")) {
            var response = post(Map.of("model", MemoryEmbeddingBridge.MODEL, "input", input));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("\uD800", "课");
        }
        assertThat(rawPost("{\"model\":\"" + MemoryEmbeddingBridge.MODEL
                + "\",\"input\":\"\\uD800\"}", "application/json").statusCode()).isEqualTo(400);
        var after = JSON.readTree(get("/internal/stats", TOKEN).body());
        assertThat(after.path("embedded_texts")).isEqualTo(before.path("embedded_texts"));
        assertThat(after.path("embedding_requests")).isEqualTo(before.path("embedding_requests"));
        assertThat(after.path("failures").asLong()).isGreaterThanOrEqualTo(before.path("failures").asLong() + 7);
    }

    @Test void enforcesStrictJsonContentTypeAndBodyLimit() throws Exception {
        for (String body : List.of("{", "{} {}", "[]", "{\"model\":\"a\",\"model\":\"b\"}"))
            assertThat(rawPost(body, "application/json").statusCode()).isEqualTo(400);
        assertThat(rawPost(" ".repeat(32769), "application/json").statusCode()).isEqualTo(413);
        assertThat(rawPost("{}", "text/plain").statusCode()).isEqualTo(415);
        assertThat(get("/v1/embeddings", TOKEN).statusCode()).isEqualTo(404);
    }

    @Test void closesIdempotentlyAndReleasesPortAndModel() throws Exception {
        var embedding = new BgeChineseEmbedding();
        var own = new MemoryEmbeddingBridge("127.0.0.1", 0, TOKEN, embedding, Duration.ofSeconds(2));
        own.start();
        int port = own.port();
        own.close();
        own.close();
        assertThatThrownBy(() -> embedding.embedDocuments(List.of("课程"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(own::start).isInstanceOf(IllegalStateException.class);
        try (var rebound = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThat(rebound.isBound()).isTrue();
        }
    }

    @Test void requestDeadlineClosesSlowBodyWithoutEncoding() throws Exception {
        try (var own = new MemoryEmbeddingBridge("127.0.0.1", 0, TOKEN, new BgeChineseEmbedding(),
                Duration.ofMillis(300))) {
            own.start();
            try (var socket = new java.net.Socket("127.0.0.1", own.port())) {
                socket.setSoTimeout(3000);
                String headers = "POST /v1/embeddings HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                        + TOKEN + "\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{";
                socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                try {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                } catch (java.net.SocketException closedConnection) {
                    // Windows 在服务端关闭未读完的请求时发送 RST；超时异常仍应让测试失败。
                    assertThat(closedConnection).isNotInstanceOf(java.net.SocketTimeoutException.class);
                }
            }
        }
    }

    @Test void rejectsOverloadWith503WhenTwoWorkersAndFourQueueSlotsAreOccupied() throws Exception {
        try (var own = new MemoryEmbeddingBridge("127.0.0.1", 0, TOKEN, new BgeChineseEmbedding(),
                Duration.ofSeconds(10))) {
            own.start();
            var sockets = new java.util.ArrayList<java.net.Socket>();
            try {
                for (int i = 0; i < 6; i++) {
                    var socket = new java.net.Socket("127.0.0.1", own.port());
                    sockets.add(socket);
                    String headers = "POST /v1/embeddings HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                            + TOKEN + "\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{";
                    socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                var response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + own.port() + "/health"))
                        .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + TOKEN).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).contains("overloaded").doesNotContain(TOKEN);
            } finally {
                for (var socket : sockets) socket.close();
            }
        }
    }

    private static HttpResponse<String> post(Object body) throws Exception {
        return rawPost(JSON.writeValueAsString(body), "application/json");
    }

    private static HttpResponse<String> rawPost(String body, String type) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri("/v1/embeddings")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) { return URI.create("http://127.0.0.1:" + bridge.port() + path); }
}
