package com.learnhub.assistant.memory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 单实例共享 HTTP 客户端；远端只提供候选标识，正文与权限由本地事实源复核。 */
public final class Mem0HttpClient implements Mem0Client, AutoCloseable {
    private static final int MAX_REQUEST = 4096;
    private static final int MAX_RESPONSE = 16384;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(MAX_RESPONSE).build())
            .build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI base;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Mem0HttpClient(URI base, String token, Duration timeout) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || token == null || token.length() < 32 || token.length() > 256
                || token.chars().anyMatch(c -> c < 33 || c > 126)
                || timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid private memory service configuration");
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.token = token;
        this.timeout = timeout;
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "mem0-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        http = HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void upsert(long owner, String id, long version, String category, String text) {
        validateIdVersion(id, version);
        if (category == null || !Set.of("STYLE", "PACE", "LEVEL", "GOAL").contains(category)) throw unavailable();
        validateText(text, 200);
        JsonNode response = call("PUT", "/preferences/" + id, Map.of("ownerKey", ownerKey(owner),
                "version", version, "category", category, "text", text));
        validateReply(response, id, version, false);
    }

    @Override public void delete(long owner, String id, long version) {
        validateIdVersion(id, version);
        JsonNode response = call("DELETE", "/preferences/" + id,
                Map.of("ownerKey", ownerKey(owner), "version", version));
        validateReply(response, id, version, true);
    }

    @Override public List<Hit> search(long owner, String query, int limit) {
        if (query == null || limit < 1) throw unavailable();
        validateUnicode(query);
        int end = Math.min(200, query.length());
        if (end < query.length() && end > 0 && Character.isHighSurrogate(query.charAt(end - 1))) end--;
        String bounded = query.substring(0, end);
        validateText(bounded, 200);
        int cappedLimit = Math.min(12, limit);
        JsonNode response = call("POST", "/preferences/search",
                Map.of("ownerKey", ownerKey(owner), "query", bounded, "limit", cappedLimit));
        fields(response, Set.of("items"));
        JsonNode items = response.get("items");
        if (!items.isArray() || items.size() > cappedLimit) throw unavailable();
        List<Hit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            fields(item, Set.of("preferenceId", "version"));
            String id = readId(item.get("preferenceId"));
            long version = readVersion(item.get("version"));
            if (!seen.add(id)) throw unavailable();
            result.add(new Hit(id, version));
        }
        return List.copyOf(result);
    }

    private JsonNode call(String method, String path, Map<String, Object> request) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        AutoCloseable registration = null;
        AssistantIoScope scope = AssistantIoScope.current();
        try {
            if (closed.get()) throw unavailable();
            Duration budget = timeout;
            if (scope != null && scope.remaining().compareTo(budget) < 0) budget = scope.remaining();
            long deadline = System.nanoTime() + budget.toNanos();
            byte[] body = json.writeValueAsBytes(request);
            if (body.length > MAX_REQUEST) throw unavailable();
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(base + path)).timeout(budget)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Accept", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
            future = http.sendAsync(httpRequest, ignored -> new LimitedBodySubscriber());
            active.add(future);
            if (closed.get()) future.cancel(true);
            CompletableFuture<HttpResponse<byte[]>> cancellable = future;
            if (scope != null) registration = scope.onCancel(() -> cancellable.cancel(true));
            HttpResponse<byte[]> response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (scope != null) scope.remaining();
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) throw unavailable();
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw unavailable();
            return parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (scope != null) scope.remaining();
            throw unavailable();
        } catch (BusinessException business) {
            throw business;
        } catch (Exception failure) {
            if (scope != null) scope.remaining();
            throw unavailable();
        } finally {
            if (future != null) { active.remove(future); if (!future.isDone()) future.cancel(true); }
            if (registration != null) try { registration.close(); } catch (Exception ignored) { }
        }
    }

    private String ownerKey(long owner) {
        if (owner <= 0) throw unavailable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("learnhub:mem0:owner:v1\0" + owner).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw unavailable(); }
    }

    private static void validateReply(JsonNode response, String id, long version, boolean deleted) {
        fields(response, deleted ? Set.of("preferenceId", "version", "deleted") : Set.of("preferenceId", "version"));
        if (!id.equals(readId(response.get("preferenceId"))) || readVersion(response.get("version")) != version
                || deleted && (!response.get("deleted").isBoolean() || !response.get("deleted").booleanValue())) throw unavailable();
    }

    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw unavailable();
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw unavailable();
    }

    private static String readId(JsonNode value) {
        if (value == null || !value.isTextual()) throw unavailable();
        String id = value.textValue();
        try { if (!UUID.fromString(id).toString().equals(id)) throw unavailable(); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
        return id;
    }

    private static long readVersion(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) throw unavailable();
        return node.longValue();
    }

    private static void validateIdVersion(String id, long version) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id) || version < 1 || version > Integer.MAX_VALUE) throw unavailable(); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
    }

    private static void validateText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max) throw unavailable();
        validateUnicode(text);
    }

    private static void validateUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw unavailable();
            } else if (Character.isLowSurrogate(value)) throw unavailable();
        }
    }

    private static BusinessException unavailable() { return new BusinessException("MEMORY_UNAVAILABLE", "学习偏好服务暂时不可用"); }

    @PreDestroy @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    /** BodySubscriber 在累计分配超过上限前取消订阅，get 的时限覆盖整个响应体。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - output.size()) {
                    subscription.cancel(); result.completeExceptionally(unavailable()); return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(unavailable()); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
