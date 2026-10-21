package com.learnhub.assistant.chat.model.transport;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 可迁移到生产配置的 LangChain4j HTTP SPI builder。
 * 取消回调在 execute 返回前同步注册，因此不依赖首个 SSE delta。
 */
public final class BoundedJdkHttpClientBuilder implements HttpClientBuilder, AutoCloseable {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 262_144;

    private final SharedJdkHttpTransport shared;
    private final Set<BoundedJdkHttpClient> clients = ConcurrentHashMap.newKeySet();
    private Duration readTimeout = Duration.ofSeconds(45);
    private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private Consumer<String> beforeRequest;
    private boolean closed;

    public BoundedJdkHttpClientBuilder(SharedJdkHttpTransport shared) {
        this.shared = java.util.Objects.requireNonNull(shared, "shared");
    }

    @Override
    public Duration connectTimeout() {
        return shared.connectTimeout();
    }

    @Override
    public BoundedJdkHttpClientBuilder connectTimeout(Duration timeout) {
        // SDK会按本轮剩余时间回填此值；共享池连接超时不能由单次请求改写。
        // 更短的本轮截止由HttpRequest.timeout(readTimeout)及AssistantIoScope取消共同约束。
        positive(timeout, "connectTimeout");
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public BoundedJdkHttpClientBuilder readTimeout(Duration timeout) {
        this.readTimeout = positive(timeout, "readTimeout");
        return this;
    }

    public BoundedJdkHttpClientBuilder maxResponseBytes(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maximum;
        return this;
    }

    /** 回调在完整 SDK 正文交给传输前同步运行，不传入 headers，禁止记录正文。 */
    public BoundedJdkHttpClientBuilder beforeRequest(Consumer<String> callback) {
        this.beforeRequest = java.util.Objects.requireNonNull(callback);
        return this;
    }

    @Override
    public synchronized HttpClient build() {
        if (closed) throw new IllegalStateException("LangChain4j HTTP builder is closed");
        BoundedJdkHttpClient client = shared.borrow(readTimeout, maxResponseBytes);
        clients.add(client);
        client.onClose(clients::remove);
        Consumer<String> check = beforeRequest;
        if (check == null) return client;
        return new HttpClient() {
            @Override public dev.langchain4j.http.client.SuccessfulHttpResponse execute(
                    dev.langchain4j.http.client.HttpRequest request) {
                check.accept(request.body());
                return client.execute(request);
            }
            @Override public void execute(dev.langchain4j.http.client.HttpRequest request,
                    dev.langchain4j.http.client.sse.ServerSentEventParser parser,
                    dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                check.accept(request.body());
                client.execute(request, parser, listener);
            }
        };
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (BoundedJdkHttpClient client : Set.copyOf(clients)) client.close();
        clients.clear();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

