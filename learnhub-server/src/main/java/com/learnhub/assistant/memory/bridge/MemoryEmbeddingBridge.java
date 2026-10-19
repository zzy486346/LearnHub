package com.learnhub.assistant.memory.bridge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.vector.BgeChineseEmbedding;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 独立本地进程入口；不创建 Spring 容器，不读取供应商配置。 */
public final class MemoryEmbeddingBridge implements AutoCloseable {
    public static final String MODEL = "learnhub-bge-zh-v1";
    private static final int MAX_BODY = 32 * 1024;
    private static final Set<String> FIELDS = Set.of("model", "input", "dimensions", "encoding_format");
    private static final ThreadLocal<Boolean> OVERLOADED = new ThreadLocal<>();
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(MAX_BODY).build())
            .build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final BgeChineseEmbedding embedding;
    private final byte[] credentialHash;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong texts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final long timeoutMillis;
    private boolean started;
    private boolean closed;

    /** 构造成功后接管 embedding 的关闭责任。端口 0 用于隔离测试。 */
    public MemoryEmbeddingBridge(String host, int port, String token, BgeChineseEmbedding embedding,
            Duration requestTimeout) throws IOException {
        if (token == null || token.length() < 24 || token.length() > 256
                || token.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("Invalid bridge service credential");
        if (host == null || host.isBlank() || port < 0 || port > 65535 || embedding == null
                || requestTimeout == null || requestTimeout.toMillis() < 100 || requestTimeout.toSeconds() > 60)
            throw new IllegalArgumentException("Invalid bridge configuration");
        this.embedding = embedding;
        this.credentialHash = digest("Bearer " + token);
        this.timeoutMillis = requestTimeout.toMillis();
        server = HttpServer.create(new InetSocketAddress(host, port), 6);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
                runnable -> new Thread(runnable, "memory-embedding-worker"), (runnable, executor) -> {
                    // JDK dispatcher 只执行快速 503 分支，不执行推理或排队等待。
                    OVERLOADED.set(true);
                    try { runnable.run(); } finally { OVERLOADED.remove(); }
                });
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-embedding-deadline");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public synchronized void start() {
        if (closed || started) throw new IllegalStateException("Bridge cannot start");
        server.start();
        started = true;
    }

    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        if (Boolean.TRUE.equals(OVERLOADED.get())) {
            failures.incrementAndGet();
            try { send(exchange, 503, error("overloaded")); } finally { exchange.close(); }
            return;
        }
        var deadline = timer.schedule(exchange::close, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            var authorization = exchange.getRequestHeaders().get("Authorization");
            if (authorization == null || authorization.size() != 1
                    || !MessageDigest.isEqual(credentialHash, digest(authorization.get(0)))) {
                fail(exchange, 401, "unauthorized");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) { fail(exchange, 400, "invalid_request"); return; }
            if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(path)) {
                send(exchange, 200, Map.of("status", "ok", "model", MODEL));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/internal/stats".equals(path)) {
                send(exchange, 200, Map.of("embedding_requests", requests.get(), "embedded_texts", texts.get(),
                        "failures", failures.get()));
            } else if ("POST".equals(exchange.getRequestMethod()) && "/v1/embeddings".equals(path)) {
                encode(exchange);
            } else { fail(exchange, 404, "not_found"); }
        } catch (InvalidRequest failure) {
            fail(exchange, failure.status, failure.code);
        } catch (IllegalArgumentException failure) {
            fail(exchange, 400, "invalid_input");
        } catch (IOException failure) {
            failures.incrementAndGet(); // 客户端断连或超时，不回显异常。
        } catch (RuntimeException failure) {
            fail(exchange, 503, "embedding_unavailable");
        } finally {
            deadline.cancel(false);
            exchange.close();
        }
    }

    private void encode(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))
            throw new InvalidRequest(415, "unsupported_media_type");
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new InvalidRequest(413, "body_too_large");
        JsonNode request;
        try { request = json.readTree(body); }
        catch (IOException failure) { throw new InvalidRequest(400, "invalid_json"); }
        if (request == null || !request.isObject()) throw new InvalidRequest(400, "invalid_request");
        var names = request.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new InvalidRequest(400, "unknown_field");
        if (!request.path("model").isTextual() || !MODEL.equals(request.path("model").textValue()))
            throw new InvalidRequest(400, "invalid_model");
        if (request.has("dimensions") && (!request.get("dimensions").isIntegralNumber()
                || !request.get("dimensions").canConvertToInt() || request.get("dimensions").intValue() != 512))
            throw new InvalidRequest(400, "invalid_dimensions");
        if (request.has("encoding_format") && (!request.get("encoding_format").isTextual()
                || !"float".equals(request.get("encoding_format").textValue())))
            throw new InvalidRequest(400, "invalid_encoding_format");
        JsonNode input = request.path("input");
        List<String> inputs = new ArrayList<>();
        if (input.isTextual()) inputs.add(input.textValue());
        else if (input.isArray() && input.size() >= 1 && input.size() <= 8) {
            for (JsonNode item : input) {
                if (!item.isTextual()) throw new InvalidRequest(400, "invalid_input");
                inputs.add(item.textValue());
            }
        } else throw new InvalidRequest(400, "invalid_input");
        int tokens = 0;
        for (String text : inputs) {
            if (text.isBlank() || text.length() > 600) throw new InvalidRequest(400, "invalid_input");
            int count = embedding.countTokens(text); // 检查 Unicode，实际计数不截断。
            if (count > 480) throw new InvalidRequest(400, "token_limit");
            tokens += count;
        }
        requests.incrementAndGet();
        var vectors = embedding.embedDocuments(inputs);
        texts.addAndGet(vectors.size());
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i).vector();
            if (vector.length != 512) throw new IllegalStateException("Invalid embedding dimension");
            for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("Invalid embedding value");
            data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
        }
        send(exchange, 200, Map.of("object", "list", "data", data, "model", MODEL,
                "usage", Map.of("prompt_tokens", tokens, "total_tokens", tokens)));
    }

    private void fail(HttpExchange exchange, int status, String code) throws IOException {
        failures.incrementAndGet();
        send(exchange, status, error(code));
    }

    private static Map<String, Object> error(String code) {
        return Map.of("error", Map.of("type", "invalid_request_error", "code", code, "message", code));
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(started ? 2 : 0);
        workers.shutdown();
        try { if (!workers.awaitTermination(3, TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch (InterruptedException interrupted) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        timer.shutdownNow();
        embedding.close();
    }

    public static void main(String[] args) throws Exception {
        // JDK HttpServer 的头部/响应时限；应用另为每个已进入 handler 的请求设截止时间。
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "8");
        String token = System.getenv("LEARNHUB_MEMORY_EMBED_TOKEN");
        String host = System.getenv().getOrDefault("LEARNHUB_MEMORY_EMBED_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("LEARNHUB_MEMORY_EMBED_PORT", "8081"));
        if (token == null || token.isBlank()) throw new IllegalStateException("Bridge service credential required");
        BgeChineseEmbedding embedding = new BgeChineseEmbedding();
        MemoryEmbeddingBridge bridge;
        try { bridge = new MemoryEmbeddingBridge(host, port, token, embedding, Duration.ofSeconds(10)); }
        catch (Exception failure) { embedding.close(); throw failure; }
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "memory-embedding-shutdown"));
        bridge.start();
    }

    private static final class InvalidRequest extends RuntimeException {
        private final int status;
        private final String code;
        private InvalidRequest(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
