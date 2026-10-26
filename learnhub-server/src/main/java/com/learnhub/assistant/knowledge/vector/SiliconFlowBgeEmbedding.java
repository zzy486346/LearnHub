package com.learnhub.assistant.knowledge.vector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** SiliconFlow 文本 Embedding；不加载本地 ONNX，不记录凭据、输入或远端错误正文。 */
public final class SiliconFlowBgeEmbedding implements CourseEmbedding {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SiliconFlowBgeEmbedding.class);
    public static final String MODEL = "BAAI/bge-large-zh-v1.5";
    public static final String MODEL_ID = "sf-bge-large-zh-v15-t480-o32-v1";
    public static final String TABLE = "course_embeddings_sf_bge_large_zh_v1";
    private static final int DIMENSION = 1024;
    private static final int MAX_RESPONSE = 1_048_576;
    private static final int MAX_REQUEST = 131_072;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(MAX_RESPONSE).maxNumberLength(64).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final String key;
    private final Duration timeout;
    private final HttpClient http;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong encoded = new AtomicLong();
    private final HuggingFaceTokenizer tokenizer;

    public SiliconFlowBgeEmbedding(URI endpoint, String apiKey, Duration timeout) {
        boolean production = endpoint != null && "https".equals(endpoint.getScheme())
                && "api.siliconflow.cn".equals(endpoint.getHost()) && endpoint.getPort() == -1;
        boolean mock = endpoint != null && "http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost());
        if ((!production && !mock) || !"/v1/embeddings".equals(endpoint.getPath())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || apiKey == null || apiKey.isBlank() || apiKey.length() > 256
                || apiKey.chars().anyMatch(c -> c < 33 || c > 126)
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid SiliconFlow embedding configuration");
        this.endpoint = endpoint; this.key = apiKey; this.timeout = timeout;
        // 只加载分词器，不构造 BGE/ONNX 推理模型。官方large tokenizer与打包资源的词表、
        // pre/post processor、added_tokens相同；唯一归一化差别是large启用lowercase。
        System.setProperty("ai.djl.offline", "true");
        System.setProperty("OPT_OUT_TRACKING", "true");
        if (!ai.djl.util.Utils.isOfflineMode()) throw new IllegalStateException("Offline tokenizer required");
        try (var input = SiliconFlowBgeEmbedding.class.getResourceAsStream("/bge-small-zh-v1.5-q-tokenizer.json")) {
            if (input == null) throw new IllegalStateException("BGE tokenizer resource missing");
            var config = json.readTree(input);
            ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("normalizer")).put("lowercase", true);
            try (var bytes = new java.io.ByteArrayInputStream(json.writeValueAsBytes(config))) {
                tokenizer = HuggingFaceTokenizer.newInstance(bytes, Map.of(
                        "truncation", "false", "padding", "false", "addSpecialTokens", "true"));
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("BGE large tokenizer initialization failed");
        }
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "siliconflow-embedding-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        http = HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String modelId() { return MODEL_ID; }
    @Override public String tableName() { return TABLE; }
    @Override public int dimension() { return DIMENSION; }
    @Override public long embeddingCount() { return encoded.get(); }

    /** 使用与官方large模型一致的WordPiece配置，包含CLS/SEP且禁用截断。 */
    public int countTokens(String text) {
        requireOpen(); validate(text, 2000);
        synchronized (tokenizer) { requireOpen(); return tokenizer.encode(text).getIds().length; }
    }

    @Override public List<String> splitDocument(String text) {
        requireOpen();
        validate(text, 600);
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.length();
            while (countTokens(text.substring(start, end)) > 480) end = text.offsetByCodePoints(end, -1);
            if (end <= start) throw new IllegalArgumentException("Cannot split embedding input");
            parts.add(text.substring(start, end));
            if (parts.size() > MAX_PARTS_PER_CHUNK) throw new IllegalArgumentException("Embedding part limit exceeded");
            if (end == text.length()) break;
            int next = end;
            for (int candidate = text.offsetByCodePoints(end, -1); candidate > start;
                    candidate = text.offsetByCodePoints(candidate, -1)) {
                if (countTokens(text.substring(candidate, end)) > 32) break;
                next = candidate;
            }
            start = next;
        }
        return List.copyOf(parts);
    }

    @Override public List<Embedding> embedDocuments(List<String> texts) {
        requireOpen();
        if (texts == null || texts.isEmpty() || texts.size() > 32)
            throw new IllegalArgumentException("Invalid embedding batch");
        for (String text : texts) {
            validate(text, 600);
            if (countTokens(text) > 480)
                throw new IllegalArgumentException("Embedding document token limit exceeded");
        }
        return call(List.copyOf(texts));
    }

    @Override public Embedding embedQuery(String question) {
        requireOpen();
        validate(question, 500);
        String input = BgeChineseEmbedding.QUERY_PREFIX + question;
        if (countTokens(input) > 512)
            throw new IllegalArgumentException("Embedding query token limit exceeded");
        return call(List.of(input)).get(0);
    }

    private List<Embedding> call(List<String> texts) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        AutoCloseable registration = null;
        AssistantIoScope scope = AssistantIoScope.current();
        boolean scopeLimited = false;
        try {
            requireOpen();
            Duration budget = timeout;
            if (scope != null) {
                Duration remaining = scope.remaining();
                if (remaining.compareTo(budget) < 0) { budget = remaining; scopeLimited = true; }
            }
            long deadline = System.nanoTime() + budget.toNanos();
            // dimensions 仅限 Qwen；BAAI 模型固定 1024 维，不发送该字段。
            byte[] body = json.writeValueAsBytes(Map.of("model", MODEL, "input", texts, "encoding_format", "float"));
            if (body.length > MAX_REQUEST) throw unavailable();
            var request = HttpRequest.newBuilder(endpoint).timeout(budget)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            future = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
            active.add(future);
            if (closed.get()) future.cancel(true);
            CompletableFuture<?> cancellable = future;
            if (scope != null) registration = scope.onCancel(() -> cancellable.cancel(true));
            var response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (scope != null) scope.remaining();
            if (response.statusCode() != 200) {
                log.warn("远程向量服务请求失败 status={}", response.statusCode());
                throw unavailable();
            }
            if (!response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) throw unavailable();
            List<Embedding> result = decode(json.readTree(response.body()), texts.size());
            encoded.addAndGet(result.size());
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (scope != null) scope.remaining();
            throw unavailable();
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            if (scope != null) {
                scope.remaining();
                if (scopeLimited && timeoutSignal(failure))
                    throw new BusinessException("ASSISTANT_DEADLINE_EXCEEDED", "助理请求已超时");
            }
            throw unavailable();
        } finally {
            if (future != null) { active.remove(future); if (!future.isDone()) future.cancel(true); }
            if (registration != null) try { registration.close(); } catch (Exception ignored) { }
        }
    }

    private List<Embedding> decode(JsonNode root, int count) {
        if (root == null || !root.isObject() || !"list".equals(root.path("object").asText())
                || !MODEL.equals(root.path("model").asText()) || !root.path("data").isArray()
                || root.path("data").size() != count) throw unavailable();
        JsonNode usage = root.path("usage");
        for (String field : List.of("prompt_tokens", "total_tokens")) {
            if (!usage.path(field).isIntegralNumber() || !usage.path(field).canConvertToLong()
                    || usage.path(field).longValue() < 0) throw unavailable();
        }
        if (usage.path("total_tokens").longValue() < usage.path("prompt_tokens").longValue()) throw unavailable();
        Embedding[] result = new Embedding[count];
        for (JsonNode item : root.path("data")) {
            JsonNode index = item.path("index");
            if (!"embedding".equals(item.path("object").asText()) || !index.isIntegralNumber()
                    || !index.canConvertToInt() || index.intValue() < 0 || index.intValue() >= count
                    || result[index.intValue()] != null || !item.path("embedding").isArray()
                    || item.path("embedding").size() != DIMENSION) throw unavailable();
            float[] vector = new float[DIMENSION];
            double norm = 0;
            for (int i = 0; i < DIMENSION; i++) {
                JsonNode value = item.path("embedding").get(i);
                float number = value.floatValue();
                if (!value.isNumber() || !Float.isFinite(number)) throw unavailable();
                vector[i] = number;
                norm += (double) number * number;
            }
            if (!Double.isFinite(norm) || norm <= 0) throw unavailable();
            double length = Math.sqrt(norm);
            for (int i = 0; i < DIMENSION; i++) vector[i] = (float) (vector[i] / length);
            result[index.intValue()] = Embedding.from(vector);
        }
        return List.of(result);
    }

    private static boolean timeoutSignal(Throwable failure) {
        for (Throwable part = failure; part != null; part = part.getCause())
            if (part instanceof HttpTimeoutException || part instanceof TimeoutException
                    || part instanceof java.util.concurrent.CancellationException) return true;
        return false;
    }

    private static void validate(String text, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum)
            throw new IllegalArgumentException("Invalid embedding text");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode text");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid Unicode text");
        }
    }

    private void requireOpen() { if (closed.get()) throw new IllegalStateException("Embedding component closed"); }
    private static BusinessException unavailable() {
        return new BusinessException("VECTOR_EMBEDDING_UNAVAILABLE", "远程向量服务暂时不可用");
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            synchronized (tokenizer) { tokenizer.close(); }
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
                if (buffer.remaining() > MAX_RESPONSE - output.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Embedding response too large"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
