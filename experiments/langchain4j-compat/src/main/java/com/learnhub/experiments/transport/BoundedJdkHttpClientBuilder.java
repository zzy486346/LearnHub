package com.learnhub.experiments.transport;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 可迁移到生产配置的 LangChain4j HTTP SPI builder。
 * 取消回调在 execute 返回前同步注册，因此不依赖首个 SSE delta。
 */
public final class BoundedJdkHttpClientBuilder implements HttpClientBuilder, AutoCloseable {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 262_144;

    private final Consumer<Runnable> cancellationRegistrar;
    private final Supplier<AutoCloseable> registrationCleanup;
    private final Set<BoundedJdkHttpClient> clients = ConcurrentHashMap.newKeySet();
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(45);
    private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private int threads = 4;
    private int queueCapacity = 32;

    public BoundedJdkHttpClientBuilder(Consumer<Runnable> cancellationRegistrar,
                                       Supplier<AutoCloseable> registrationCleanup) {
        this.cancellationRegistrar = Objects.requireNonNull(cancellationRegistrar, "cancellationRegistrar");
        this.registrationCleanup = Objects.requireNonNull(registrationCleanup, "registrationCleanup");
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public BoundedJdkHttpClientBuilder connectTimeout(Duration timeout) {
        this.connectTimeout = positive(timeout, "connectTimeout");
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

    public BoundedJdkHttpClientBuilder executorBounds(int threads, int queueCapacity) {
        if (threads < 2 || threads > 32 || queueCapacity < 1 || queueCapacity > 1024) {
            throw new IllegalArgumentException("invalid executor bounds");
        }
        this.threads = threads;
        this.queueCapacity = queueCapacity;
        return this;
    }

    @Override
    public HttpClient build() {
        BoundedJdkHttpClient client = new BoundedJdkHttpClient(connectTimeout, readTimeout,
                maxResponseBytes, threads, queueCapacity, cancellationRegistrar, registrationCleanup,
                clients::remove);
        clients.add(client);
        return client;
    }

    @Override
    public void close() {
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
