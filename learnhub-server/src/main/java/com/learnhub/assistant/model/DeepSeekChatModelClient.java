package com.learnhub.assistant.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class DeepSeekChatModelClient implements ChatModelClient, AutoCloseable {
    private static final URI PRODUCTION_BASE_URI = URI.create("https://api.deepseek.com");

    private final ObjectMapper mapper;
    private final AiProperties properties;
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ExecutorService transportExecutor;

    DeepSeekChatModelClient(ObjectMapper mapper, AiProperties properties) {
        this(mapper, properties, PRODUCTION_BASE_URI, false);
    }

    DeepSeekChatModelClient(ObjectMapper mapper, AiProperties properties, URI testBaseUri) {
        this(mapper, properties, testBaseUri, true);
    }

    private DeepSeekChatModelClient(
            ObjectMapper mapper, AiProperties properties, URI baseUri, boolean testEndpoint) {
        this.mapper = mapper;
        this.properties = properties;
        properties.validateForDeepSeek();
        if (testEndpoint) requireLoopbackEndpoint(baseUri);
        this.baseUri = baseUri;
        int threads = properties.getTransportThreads();
        this.transportExecutor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(32, threads * 8)),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(transportExecutor)
                .build();
    }

    @Override
    public ChatResponse generate(ChatRequest request) {
        byte[] requestBody = serializeRequest(request);
        if (requestBody.length > properties.getMaxInputBytes()) {
            throw new ModelClientException(
                    "AI_MODEL_INPUT_TOO_LARGE", "模型输入超过本地 UTF-8 字节预算；该预算不是精确 Token 计数");
        }

        Duration deadline = request.timeout().compareTo(properties.getRequestTimeout()) < 0
                ? request.timeout()
                : properties.getRequestTimeout();
        RestClient client = RestClient.builder()
                .baseUrl(baseUri.toString())
                .requestFactory(new AsyncJdkRequestFactory(deadline))
                .build();
        try {
            return client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(properties.getApiKey().trim()))
                    .contentLength(requestBody.length)
                    .body(outputStream -> outputStream.write(requestBody))
                    .exchange((httpRequest, response) -> readResponse(response));
        } catch (ResourceAccessException exception) {
            if (hasCause(exception, RequestCancelledIOException.class)) {
                throw new ModelClientException("AI_MODEL_CANCELLED", "模型请求已取消", exception);
            }
            if (hasCause(exception, java.util.concurrent.RejectedExecutionException.class)) {
                throw new ModelClientException("AI_MODEL_BUSY", "模型传输队列已满，请稍后重试", exception);
            }
            if (hasCause(exception, ResponseTooLargeIOException.class)) {
                throw new ModelClientException("AI_MODEL_RESPONSE_TOO_LARGE", "模型响应超过本地字节上限");
            }
            if (hasCause(exception, HttpTimeoutException.class)
                    || hasCause(exception, SocketTimeoutException.class)) {
                throw new ModelClientException("AI_MODEL_TIMEOUT", "模型请求超时", exception);
            }
            throw new ModelClientException("AI_MODEL_UNAVAILABLE", "模型服务暂时不可用", exception);
        } catch (RestClientException exception) {
            if (hasCause(exception, java.util.concurrent.RejectedExecutionException.class)) {
                throw new ModelClientException("AI_MODEL_BUSY", "模型传输队列已满，请稍后重试", exception);
            }
            throw new ModelClientException("AI_MODEL_UNAVAILABLE", "模型服务暂时不可用", exception);
        }
    }

    private ChatResponse readResponse(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status < 200 || status >= 300) {
            if (status == 401) {
                throw new ModelClientException("AI_MODEL_AUTHENTICATION_FAILED", "模型服务认证失败");
            }
            if (status == 429) {
                throw new ModelClientException("AI_MODEL_RATE_LIMITED", "模型服务请求过于频繁");
            }
            throw new ModelClientException("AI_MODEL_PROVIDER_ERROR", "模型服务返回异常状态：" + status);
        }
        byte[] bytes = response.getBody().readAllBytes();
        if (bytes.length == 0) throw new ModelClientException("AI_MODEL_EMPTY_RESPONSE", "模型返回空响应");
        return parseResponse(bytes);
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(int maxBytes) {
        return responseInfo -> new BoundedBodySubscriber(maxBytes);
    }

    private final class AsyncJdkRequestFactory implements ClientHttpRequestFactory {
        private final Duration timeout;

        private AsyncJdkRequestFactory(Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new AsyncJdkClientHttpRequest(uri, httpMethod, timeout);
        }
    }

    private final class AsyncJdkClientHttpRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final Duration timeout;
        private final HttpHeaders headers = new HttpHeaders();
        private final Map<String, Object> attributes = new HashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private AsyncJdkClientHttpRequest(URI uri, HttpMethod method, Duration timeout) {
            this.uri = uri;
            this.method = method;
            this.timeout = timeout;
        }

        @Override
        public HttpMethod getMethod() { return method; }

        @Override
        public URI getURI() { return uri; }

        public Map<String, Object> getAttributes() { return attributes; }

        @Override
        public HttpHeaders getHeaders() { return headers; }

        @Override
        public OutputStream getBody() { return body; }

        @Override
        public ClientHttpResponse execute() throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    values.forEach(value -> builder.header(name, value));
                }
            });
            builder.method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            CompletableFuture<HttpResponse<byte[]>> future;
            try {
                future = httpClient.sendAsync(builder.build(), boundedBodyHandler(properties.getMaxResponseBytes()));
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                throw new IOException("AI transport queue is full", exception);
            }
            try {
                return new BufferedClientHttpResponse(future.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw timeout(exception);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new RequestCancelledIOException(exception);
            } catch (CancellationException exception) {
                throw new RequestCancelledIOException(exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (hasCause(cause, ResponseTooLargeException.class)) {
                    throw new ResponseTooLargeIOException(cause);
                }
                if (hasCause(cause, HttpTimeoutException.class)) throw timeout(cause);
                if (cause instanceof IOException ioException) throw ioException;
                throw new IOException("DeepSeek HTTP transport failed", cause);
            }
        }

        private SocketTimeoutException timeout(Throwable cause) {
            SocketTimeoutException exception = new SocketTimeoutException("DeepSeek request timed out");
            exception.initCause(cause);
            return exception;
        }
    }

    private static final class BufferedClientHttpResponse implements ClientHttpResponse {
        private final HttpResponse<byte[]> response;
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayInputStream body;

        private BufferedClientHttpResponse(HttpResponse<byte[]> response) {
            this.response = response;
            response.headers().map().forEach(headers::put);
            this.body = new ByteArrayInputStream(response.body());
        }

        @Override
        public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }

        @Override
        public String getStatusText() { return ""; }

        @Override
        public HttpHeaders getHeaders() { return headers; }

        @Override
        public InputStream getBody() { return body; }

        @Override
        public void close() {
            // The bounded response is already fully buffered; there is no live network body to drain.
        }
    }

    private static final class RequestCancelledIOException extends IOException {
        private RequestCancelledIOException(Throwable cause) { super("DeepSeek request cancelled", cause); }
    }

    private static final class ResponseTooLargeIOException extends IOException {
        private ResponseTooLargeIOException(Throwable cause) { super("DeepSeek response too large", cause); }
    }

    private byte[] serializeRequest(ChatRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", properties.getModel());
        root.put("stream", false);
        root.put("max_tokens", Math.min(request.maxOutputTokens(), properties.getMaxOutputTokens()));
        root.putObject("thinking").put("type", "disabled");
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (message.content() == null) node.putNull("content");
            else node.put("content", message.content());
            if (message.toolCallId() != null) node.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall toolCall : message.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", toolCall.id());
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", toolCall.name());
                    function.put("arguments", toolCall.arguments());
                }
            }
        }
        if (!request.tools().isEmpty()) {
            root.set("tools", mapper.valueToTree(request.tools()));
            root.put("tool_choice", "auto");
        }
        if (request.jsonOutput()) root.putObject("response_format").put("type", "json_object");
        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw new ModelClientException("AI_MODEL_REQUEST_INVALID", "模型请求无法序列化", exception);
        }
    }

    private ChatResponse parseResponse(byte[] bytes) {
        final JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException exception) {
            throw malformed(exception);
        }
        if (root == null || !root.isObject()) throw malformed(null);
        Usage usage = parseUsage(root.get("usage"));
        try {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) {
            throw malformed(null);
        }
        JsonNode choice = choices.get(0);
        String finishReason = requiredText(choice.get("finish_reason"));
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) throw malformed(null);
        String text = optionalText(message.get("content"));
        List<ToolCall> toolCalls = parseToolCalls(message.get("tool_calls"));

        switch (finishReason) {
            case "stop" -> {
                if (text.isBlank() || !toolCalls.isEmpty()) {
                    throw new ModelClientException("AI_MODEL_EMPTY_RESPONSE", "模型未返回可用文本");
                }
            }
            case "tool_calls" -> {
                if (toolCalls.isEmpty()) throw malformed(null);
            }
            case "length" -> throw new ModelClientException("AI_MODEL_TRUNCATED", "模型输出因长度上限被截断");
            case "content_filter" ->
                    throw new ModelClientException("AI_MODEL_CONTENT_FILTERED", "模型输出被内容策略拦截");
            case "insufficient_system_resource" ->
                    throw new ModelClientException("AI_MODEL_UNAVAILABLE", "模型推理资源暂时不足");
            case "aborted" -> throw new ModelClientException("AI_MODEL_ABORTED", "模型服务中止了生成");
            default -> throw new ModelClientException("AI_MODEL_UNKNOWN_FINISH_REASON", "模型返回未知结束原因");
        }
        return new ChatResponse(text, toolCalls, usage, finishReason, "DEEPSEEK");
        } catch (ModelClientException failure) {
            // 已收到 usage 的截断/策略拒绝等失败也有消耗，不能丢失已知计费事实。
            throw failure.withUsage(usage);
        }
    }

    private Usage parseUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ModelClientException("AI_MODEL_MISSING_USAGE", "模型响应缺少 usage");
        }
        long prompt = requiredNonNegativeLong(node.get("prompt_tokens"));
        long completion = requiredNonNegativeLong(node.get("completion_tokens"));
        long total = requiredNonNegativeLong(node.get("total_tokens"));
        try {
            return new Usage(prompt, completion, total);
        } catch (IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw malformed(null);
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || !"function".equals(requiredText(item.get("type")))) throw malformed(null);
            JsonNode function = item.get("function");
            if (function == null || !function.isObject()) throw malformed(null);
            String arguments = requiredText(function.get("arguments"));
            try {
                JsonNode parsedArguments = mapper.readTree(arguments);
                if (parsedArguments == null || !parsedArguments.isObject()) throw malformed(null);
                calls.add(new ToolCall(
                        requiredText(item.get("id")), requiredText(function.get("name")), arguments));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw malformed(exception);
            }
        }
        return List.copyOf(calls);
    }

    private static long requiredNonNegativeLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw malformed(null);
        }
        return node.longValue();
    }

    private static String requiredText(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw malformed(null);
        return node.textValue();
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (!node.isTextual()) throw malformed(null);
        return node.textValue();
    }

    private static ModelClientException malformed(Throwable cause) {
        return cause == null
                ? new ModelClientException("AI_MODEL_MALFORMED_RESPONSE", "模型返回格式无效")
                : new ModelClientException("AI_MODEL_MALFORMED_RESPONSE", "模型返回格式无效", cause);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "learnhub-ai-transport-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void requireLoopbackEndpoint(URI uri) {
        try {
            if (uri == null || uri.getHost() == null || !InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("test DeepSeek endpoint must use a loopback address");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("test DeepSeek endpoint must use a loopback address", exception);
        }
    }

    @Override
    public void close() {
        transportExecutor.shutdownNow();
        try {
            if (!transportExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                transportExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {}
}
