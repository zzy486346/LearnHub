package com.learnhub.assistant.chat.model.transport;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public synchronized HttpClient build() {
        if (closed) throw new IllegalStateException("LangChain4j HTTP builder is closed");
        BoundedJdkHttpClient client = shared.borrow(readTimeout, maxResponseBytes);
        clients.add(client);
        client.onClose(clients::remove);
        return client;
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

