package com.learnhub.assistant.chat.model.transport;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 应用级共享 JDK HttpClient、连接池和有界 executor；每个 run 仍持有独立 operation 集合。 */
public final class SharedJdkHttpTransport implements AutoCloseable {
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();

    private final Duration connectTimeout;
    private final ThreadPoolExecutor networkExecutor;
    private final ThreadPoolExecutor readerExecutor;
    private final java.net.http.HttpClient client;
    private final Set<BoundedJdkHttpClient> borrowers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SharedJdkHttpTransport(Duration connectTimeout, int networkThreads,
                                  int readerThreads, int queueCapacity) {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative())
            throw new IllegalArgumentException("connectTimeout must be positive");
        if (networkThreads < 2 || networkThreads > 32 || readerThreads < 1 || readerThreads > 100
                || queueCapacity < 1 || queueCapacity > 1024)
            throw new IllegalArgumentException("invalid executor bounds");
        this.connectTimeout = connectTimeout;
        int poolId = POOL_SEQUENCE.incrementAndGet();
        ThreadFactory networkFactory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task,
                        "learnhub-lc4j-network-" + poolId + "-" + sequence.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };
        ThreadFactory readerFactory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task,
                        "learnhub-lc4j-reader-" + poolId + "-" + sequence.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };
        networkExecutor = pool(networkThreads, queueCapacity, networkFactory);
        readerExecutor = pool(readerThreads, queueCapacity, readerFactory);
        client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(networkExecutor)
                .build();
    }

    Duration connectTimeout() { return connectTimeout; }

    synchronized BoundedJdkHttpClient borrow(Duration readTimeout, int maxResponseBytes) {
        if (closed) throw new IllegalStateException("shared LangChain4j transport is closed");
        var borrower = new BoundedJdkHttpClient(this, readTimeout, maxResponseBytes, this::release);
        borrowers.add(borrower);
        return borrower;
    }

    private void release(BoundedJdkHttpClient borrower) {
        borrowers.remove(borrower);
    }

    java.net.http.HttpClient client() { return client; }

    void executeReader(Runnable task) { readerExecutor.execute(task); }

    @Override
    public void close() {
        Set<BoundedJdkHttpClient> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = Set.copyOf(borrowers);
        }
        active.forEach(BoundedJdkHttpClient::close);
        readerExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        boolean terminated = false;
        try {
            boolean readers = readerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            boolean network = networkExecutor.awaitTermination(5, TimeUnit.SECONDS);
            terminated = readers && network;
        } catch (InterruptedException cancelled) {
            interrupted = true;
            readerExecutor.shutdownNow();
            networkExecutor.shutdownNow();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!terminated && !interrupted)
            throw new IllegalStateException("shared LangChain4j HTTP executor did not terminate");
    }

    private static ThreadPoolExecutor pool(int threads, int queueCapacity, ThreadFactory factory) {
        var pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
