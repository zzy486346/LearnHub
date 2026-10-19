package com.learnhub.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Mem0HttpClientTest {
    private static final String TOKEN = "test-only-private-memory-service-token";
    private static final String ID = "a6bfeb8a-7f4a-4a6c-aad7-53cf019ac918";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> reply = new AtomicReference<>("{\"preferenceId\":\"" + ID + "\",\"version\":1}");
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile boolean slow;
    private HttpServer server;
    private Mem0HttpClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", exchange -> {
            try {
                received.set(JSON.readTree(exchange.getRequestBody()));
                method.set(exchange.getRequestMethod());
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                entered.countDown();
                if (slow) try { release.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) { /* 客户端取消会关闭 socket。 */ }
            finally { exchange.close(); }
        });
        server.start();
        client = new Mem0HttpClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN, Duration.ofSeconds(2));
    }

    @AfterEach void stop() {
        release.countDown();
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void writesWithServiceAuthenticationAndStableOwnerHmacInsteadOfUserId() {
        client.upsert(918273, ID, 1, "STYLE", "偏好先看代码例子");
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/preferences/" + ID);
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        String ownerA = received.get().path("ownerKey").asText();
        assertThat(ownerA).matches("[0-9a-f]{64}");
        assertThat(received.get().toString()).doesNotContain("918273", "userId", TOKEN);
        client.upsert(918273, ID, 1, "STYLE", "偏好先看代码例子");
        assertThat(received.get().path("ownerKey").asText()).isEqualTo(ownerA);
        client.upsert(918274, ID, 1, "STYLE", "偏好先看代码例子");
        assertThat(received.get().path("ownerKey").asText()).isNotEqualTo(ownerA);
    }

    @Test void deletesWithVersionedBodyAndRequiresDeletedAcknowledgement() {
        reply.set("{\"preferenceId\":\"" + ID + "\",\"version\":2,\"deleted\":true}");
        client.delete(1, ID, 2);
        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(received.get().path("version").asInt()).isEqualTo(2);
        reply.set("{\"preferenceId\":\"" + ID + "\",\"version\":2,\"deleted\":false}");
        assertUnavailable(() -> client.delete(1, ID, 2));
    }

    @Test void searchBoundsQueryWithoutSplittingSurrogatesAndReturnsOnlyIdentifiers() throws Exception {
        reply.set(JSON.writeValueAsString(Map.of("items", List.of(Map.of("preferenceId", ID, "version", 3)))));
        var hits = client.search(1, "课".repeat(199) + "😀尾部", 15);
        assertThat(hits).containsExactly(new Mem0Client.Hit(ID, 3));
        assertThat(received.get().path("query").asText()).isEqualTo("课".repeat(199));
        assertThat(received.get().path("limit").asInt()).isEqualTo(12);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/preferences/search");
    }

    @Test void rejectsForgedUuidVersionDuplicatesExtraFieldsAndMalformedJson() {
        for (String body : List.of(
                "{\"items\":[{\"preferenceId\":\"1-1-1-1-1\",\"version\":1}]}",
                "{\"items\":[{\"preferenceId\":\"" + ID + "\",\"version\":2147483648}]}",
                "{\"items\":[{\"preferenceId\":\"" + ID + "\",\"version\":1.0}]}",
                "{\"items\":[{\"preferenceId\":\"" + ID + "\",\"version\":1,\"text\":\"untrusted\"}]}",
                "{\"items\":[],\"owner\":1}", "{\"items\":[],\"items\":[]}", "{} {}", "{",
                "{\"items\":[{\"preferenceId\":\"" + ID + "\",\"version\":1},{\"preferenceId\":\"" + ID + "\",\"version\":1}]}")) {
            reply.set(body);
            assertUnavailable(() -> client.search(1, "代码", 12));
        }
    }

    @Test void hidesHttpFailureBodyAndBoundsResponseBeforeParsing() {
        status = 503;
        reply.set("private-provider-body " + TOKEN);
        assertUnavailable(() -> client.search(1, "私有查询", 3));
        status = 200;
        reply.set("x".repeat(16385));
        assertUnavailable(() -> client.search(1, "私有查询", 3));
        reply.set("{\"preferenceId\":\"" + ID + "\",\"version\":2}");
        assertUnavailable(() -> client.upsert(1, ID, 1, "STYLE", "代码例子"));
    }

    @Test void scopeCancellationCancelsPendingHttpAndPreservesSafeCancellationCode() throws Exception {
        slow = true;
        try (var scope = new AssistantIoScope(Duration.ofSeconds(3))) {
            var result = CompletableFuture.supplyAsync(() -> {
                scope.attach();
                try { client.search(1, "代码", 3); return "unexpected"; }
                catch (BusinessException failure) { return failure.getCode(); }
                finally { scope.close(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scope.cancel();
            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("ASSISTANT_CANCELLED");
        }
    }

    @Test void scopeDeadlineBoundsWholeRequestAndPreservesDeadlineCode() {
        slow = true;
        try (var scope = new AssistantIoScope(Duration.ofMillis(150))) {
            scope.attach();
            assertThatThrownBy(() -> client.search(1, "代码", 3)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_DEADLINE_EXCEEDED"));
        }
    }

    @Test void validatesLocalInputsAndClosePreventsNewRequests() {
        assertUnavailable(() -> client.upsert(1, "not-a-uuid", 1, "STYLE", "例子"));
        assertUnavailable(() -> client.upsert(1, ID, 0, "STYLE", "例子"));
        assertUnavailable(() -> client.upsert(1, ID, 1, "STYLE", "\uD800"));
        assertUnavailable(() -> client.search(1, "\uD800", 3));
        assertUnavailable(() -> client.search(0, "代码", 3));
        assertThat(received.get()).isNull();
        client.close();
        client.close();
        assertUnavailable(() -> client.search(1, "代码", 3));
    }

    @Test void conditionalConfigurationDefaultsOffAndDoesNotCreateDataSource() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(Mem0HttpConfiguration.class);
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(Mem0Client.class);
            assertThat(context).doesNotHaveBean(javax.sql.DataSource.class);
        });
        runner.withPropertyValues("learnhub.assistant.memory.enabled=true",
                "learnhub.assistant.memory.service-token=" + TOKEN,
                "learnhub.assistant.memory.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                .run(context -> {
                    assertThat(context).hasSingleBean(Mem0Client.class);
                    assertThat(context).doesNotHaveBean(javax.sql.DataSource.class);
                });
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo("MEMORY_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain(TOKEN, "private-provider-body", "私有查询", "untrusted");
            assertThat(failure.getCause()).isNull();
        });
    }
}
