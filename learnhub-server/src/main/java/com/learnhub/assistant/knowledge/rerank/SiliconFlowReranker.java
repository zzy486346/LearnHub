package com.learnhub.assistant.knowledge.rerank;

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
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** SiliconFlow 课程片段重排；远端索引只映射本次已授权输入，不作为内容事实源。 */
public final class SiliconFlowReranker implements KnowledgeReranker, AutoCloseable {
    public static final String MODEL = "BAAI/bge-reranker-v2-m3";
    private static final int MAX_BODY = 128 * 1024;
    private static final Semaphore IN_FLIGHT = new Semaphore(2);
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(MAX_BODY).maxNumberLength(64).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final String key;
    private final Duration timeout;
    private final int candidateLimit;
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private final Set<CompletableFuture<?>> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SiliconFlowReranker(URI endpoint, String key, Duration timeout, int candidateLimit) {
        validateConfiguration(endpoint, key, timeout, candidateLimit);
        this.endpoint = endpoint;
        this.key = key;
        this.timeout = timeout;
        this.candidateLimit = candidateLimit;
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "siliconflow-rerank-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        http = HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 供配置层启动前校验；不创建线程、HTTP客户端或网络连接。 */
    public static void validateConfiguration(URI endpoint, String key, Duration timeout, int candidateLimit) {
        boolean official = endpoint != null && "https".equals(endpoint.getScheme())
                && "api.siliconflow.cn".equals(endpoint.getHost()) && endpoint.getPort() == -1;
        boolean loopback = endpoint != null && "http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost());
        if ((!official && !loopback) || !"/v1/rerank".equals(endpoint.getPath())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || key == null || key.isBlank() || key.length() > 256
                || key.chars().anyMatch(value -> value < 33 || value > 126)
                || timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(10)) > 0
                || candidateLimit < 2 || candidateLimit > 50)
            throw new IllegalArgumentException("Invalid SiliconFlow rerank configuration");
    }

    @Override public int candidateLimit() { return candidateLimit; }

    @Override public List<Ranked> rerank(String query, List<String> documents) {
        validateText(query, 500);
        if (documents == null || documents.size() < 2 || documents.size() > candidateLimit)
            throw unavailable();
        documents.forEach(document -> validateText(document, 600));
        requireOpen();

        AssistantIoScope scope = AssistantIoScope.current();
        boolean acquired = false;
        boolean scopeLimited = false;
        CompletableFuture<HttpResponse<byte[]>> future = null;
        AutoCloseable registration = null;
        try {
            Duration budget = timeout;
            if (scope != null) {
                Duration remaining = scope.remaining();
                if (remaining.compareTo(budget) < 0) {
                    budget = remaining;
                    scopeLimited = true;
                }
            }
            if (!IN_FLIGHT.tryAcquire()) throw unavailable();
            acquired = true;
            if (closed.get()) throw unavailable();
            long deadline = System.nanoTime() + budget.toNanos();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", MODEL);
            payload.put("query", query);
            payload.put("documents", documents);
            payload.put("top_n", documents.size());
            payload.put("return_documents", false);
            payload.put("max_chunks_per_doc", 1);
            payload.put("overlap_tokens", 0);
            byte[] body = json.writeValueAsBytes(payload);
            if (body.length > MAX_BODY) throw unavailable();

            if (scope != null) scope.remaining();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw unavailable();
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            future = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
            active.add(future);
            if (closed.get()) future.cancel(true);
            CompletableFuture<?> cancellable = future;
            if (scope != null) registration = scope.onCancel(() -> cancellable.cancel(true));
            HttpResponse<byte[]> response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (scope != null) scope.remaining();
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) throw unavailable();
            return decode(json.readTree(response.body()), documents.size());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (scope != null) scope.remaining();
            throw unavailable();
        } catch (BusinessException business) {
            throw business;
        } catch (Exception failure) {
            if (scope != null) {
                try { scope.remaining(); }
                catch (BusinessException terminal) { throw terminal; }
                if (scopeLimited && timeoutSignal(failure))
                    throw new BusinessException("ASSISTANT_DEADLINE_EXCEEDED", "助理请求已超时");
            }
            throw unavailable();
        } finally {
            if (future != null) {
                active.remove(future);
                if (!future.isDone()) future.cancel(true);
            }
            if (registration != null) try { registration.close(); } catch (Exception ignored) { }
            if (acquired) IN_FLIGHT.release();
        }
    }

    private List<Ranked> decode(JsonNode root, int count) {
        JsonNode results = root == null ? null : root.get("results");
        if (root == null || !root.isObject() || results == null || !results.isArray() || results.size() != count)
            throw unavailable();
        boolean[] seen = new boolean[count];
        List<Ranked> ranked = new ArrayList<>(count);
        for (JsonNode item : results) {
            JsonNode index = item.get("index");
            JsonNode score = item.get("relevance_score");
            if (item == null || !item.isObject() || index == null || !index.isIntegralNumber()
                    || !index.canConvertToInt() || index.intValue() < 0 || index.intValue() >= count
                    || seen[index.intValue()] || score == null || !score.isNumber()
                    || !Double.isFinite(score.doubleValue())) throw unavailable();
            seen[index.intValue()] = true;
            ranked.add(new Ranked(index.intValue(), score.doubleValue()));
        }
        for (boolean present : seen) if (!present) throw unavailable();
        ranked.sort(java.util.Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::index));
        return List.copyOf(ranked);
    }

    private static void validateText(String text, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum) throw unavailable();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) throw unavailable();
            } else if (Character.isLowSurrogate(value)) throw unavailable();
        }
    }

    private void requireOpen() { if (closed.get()) throw unavailable(); }

    private static boolean timeoutSignal(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException
                    || current instanceof CancellationException) return true;
        return false;
    }

    private static BusinessException unavailable() {
        return new BusinessException("RERANK_UNAVAILABLE", "远程重排服务暂时不可用");
    }

    @PreDestroy @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(unavailable());
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        // 原始异常仅用于外层识别scope截止，外层统一丢弃错误正文和cause后返回受控错误。
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
