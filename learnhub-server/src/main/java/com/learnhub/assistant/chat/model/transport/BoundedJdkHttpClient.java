package com.learnhub.assistant.chat.model.transport;

import com.learnhub.assistant.runtime.AssistantIoScope;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventParsingHandle;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class BoundedJdkHttpClient implements HttpClient, AutoCloseable {
    private final SharedJdkHttpTransport shared;
    private final java.net.http.HttpClient delegate;
    private final Duration readTimeout;
    private final int maxResponseBytes;
    private final Consumer<BoundedJdkHttpClient> sharedRelease;
    private Consumer<BoundedJdkHttpClient> closeCallback = ignored -> { };
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    BoundedJdkHttpClient(SharedJdkHttpTransport shared, Duration readTimeout, int maxResponseBytes,
                         Consumer<BoundedJdkHttpClient> sharedRelease) {
        this.shared = shared;
        this.delegate = shared.client();
        this.readTimeout = readTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.sharedRelease = sharedRelease;
    }

    synchronized void onClose(Consumer<BoundedJdkHttpClient> callback) {
        if (closed) callback.accept(this); else closeCallback = callback;
    }

    @Override
    public SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
        java.net.http.HttpRequest jdkRequest = toJdkRequest(request);
        Operation operation = new Operation(error -> { });
        register(operation);
        try {
            operation.attachRegistration(requireIoScope().onCancel(operation::cancel));
            CompletableFuture<java.net.http.HttpResponse<InputStream>> future = delegate.sendAsync(
                    jdkRequest, BodyHandlers.ofInputStream());
            operation.attachFuture(future);
            java.net.http.HttpResponse<InputStream> response = future.get();
            operation.attachStream(response.body());
            operation.throwIfCancelled();
            try (InputStream body = response.body()) {
                byte[] bytes = readBounded(body);
                operation.throwIfCancelled();
                if (!successful(response.statusCode())) {
                    throw new HttpException(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                }
                return successfulResponse(response.statusCode(), response.headers().map(), bytes);
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (HttpException | ResponseTooLargeException error) {
            throw error;
        } catch (ExecutionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof java.net.http.HttpTimeoutException timeout) {
                throw new dev.langchain4j.exception.TimeoutException(timeout);
            }
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            operation.cancel();
            throw new RuntimeException(interrupted);
        } catch (IOException io) {
            if (operation.isCancelled()) throw new CancellationException("HTTP request cancelled");
            throw new RuntimeException(io);
        } finally {
            operation.finishSilently();
        }
    }

    @Override
    public void execute(dev.langchain4j.http.client.HttpRequest request, ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(listener, "listener");
        Operation operation = new Operation(listener);
        register(operation);
        try {
            operation.attachRegistration(requireIoScope().onCancel(operation::cancel));
        } catch (Throwable error) {
            operation.error(error);
            return;
        }
        if (operation.isCancelled()) return;
        try {
            CompletableFuture<java.net.http.HttpResponse<InputStream>> future = delegate.sendAsync(
                    toJdkRequest(request), BodyHandlers.ofInputStream());
            operation.attachFuture(future);
            future.whenComplete((response, failure) -> {
                if (failure != null) {
                    operation.error(unwrap(failure));
                    return;
                }
                operation.attachStream(response.body());
                try {
                    shared.executeReader(() -> readStreamingResponse(response, parser, operation));
                } catch (Throwable rejected) {
                    operation.error(rejected);
                }
            });
        } catch (Throwable error) {
            operation.error(error);
        }
    }

    private void readStreamingResponse(java.net.http.HttpResponse<InputStream> response,
                                       ServerSentEventParser parser, Operation operation) {
                if (!successful(response.statusCode())) {
                    try (InputStream body = response.body()) {
                        byte[] bytes = readBounded(body);
                        operation.error(new HttpException(response.statusCode(),
                                new String(bytes, StandardCharsets.UTF_8)));
                    } catch (Throwable error) {
                        operation.error(error);
                    }
                    return;
                }
                operation.open(successfulResponse(response.statusCode(), response.headers().map(), new byte[0]));
                try {
                    parser.parse(new BoundedInputStream(response.body(), maxResponseBytes), operation.listener());
                    operation.closeNormally();
                } catch (Throwable error) {
                    operation.error(error);
                }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Operation operation : Set.copyOf(operations)) operation.cancel();
        try {
            closeCallback.accept(this);
        } finally {
            sharedRelease.accept(this);
        }
    }

    private java.net.http.HttpRequest toJdkRequest(dev.langchain4j.http.client.HttpRequest request) {
        if (!request.formDataFields().isEmpty() || !request.formDataFiles().isEmpty()) {
            throw new IllegalArgumentException("multipart requests are not supported by this chat transport");
        }
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(readTimeout);
        request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        java.net.http.HttpRequest.BodyPublisher body = request.body() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
        HttpMethod method = request.method();
        return builder.method(method.name(), body).build();
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (BoundedInputStream bounded = new BoundedInputStream(input, maxResponseBytes);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192))) {
            bounded.transferTo(output);
            return output.toByteArray();
        }
    }

    private static SuccessfulHttpResponse successfulResponse(int status, Map<String, List<String>> headers,
                                                              byte[] body) {
        return SuccessfulHttpResponse.builder().statusCode(status).headers(headers).body(body).build();
    }

    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private synchronized void register(Operation operation) {
        if (closed) throw new IllegalStateException("HTTP client is closed");
        operations.add(operation);
    }

    private static AssistantIoScope requireIoScope() {
        AssistantIoScope scope = AssistantIoScope.current();
        if (scope == null) throw new IllegalStateException("LangChain4j transport requires AssistantIoScope");
        scope.remaining();
        return scope;
    }

    private final class Operation {
        private final ServerSentEventListener target;
        private final ServerSentEventListener serialized;
        private CompletableFuture<?> future;
        private InputStream stream;
        private AutoCloseable registration;
        private boolean cancelled;
        private boolean terminal;

        Operation(ServerSentEventListener target) {
            this.target = target;
            this.serialized = new ServerSentEventListener() {
                @Override public void onOpen(SuccessfulHttpResponse response) { Operation.this.open(response); }
                @Override public void onEvent(ServerSentEvent event, ServerSentEventContext ignored) {
                    Operation.this.event(event);
                }
                @Override public void onError(Throwable error) { Operation.this.error(error); }
                @Override public void onClose() { Operation.this.closeNormally(); }
            };
        }

        synchronized ServerSentEventListener listener() { return serialized; }

        synchronized void attachRegistration(AutoCloseable value) {
            if (terminal || cancelled) closeQuietly(value); else registration = value;
        }

        synchronized void attachFuture(CompletableFuture<?> value) {
            if (terminal || cancelled) value.cancel(true); else future = value;
        }

        synchronized void attachStream(InputStream value) {
            if (terminal || cancelled) closeQuietly(value); else stream = value;
        }

        synchronized boolean isCancelled() { return cancelled; }

        synchronized void throwIfCancelled() {
            if (cancelled) throw new CancellationException("HTTP request cancelled");
        }

        synchronized void open(SuccessfulHttpResponse response) {
            if (!terminal && !cancelled) target.onOpen(response);
        }

        synchronized void event(ServerSentEvent event) {
            if (!terminal && !cancelled) {
                ServerSentEventParsingHandle handle = new ServerSentEventParsingHandle() {
                    @Override public void cancel() { Operation.this.cancel(); }
                    @Override public boolean isCancelled() { return Operation.this.isCancelled(); }
                };
                target.onEvent(event, new ServerSentEventContext(handle));
            }
        }

        synchronized void error(Throwable error) {
            if (terminal || cancelled || error instanceof CancellationException) return;
            terminal = true;
            try {
                target.onError(error);
            } finally {
                cleanup();
            }
        }

        synchronized void closeNormally() {
            if (terminal || cancelled) return;
            terminal = true;
            try {
                target.onClose();
            } finally {
                cleanup();
            }
        }

        synchronized void finishSilently() {
            if (terminal || cancelled) return;
            terminal = true;
            cleanup();
        }

        synchronized void cancel() {
            if (terminal || cancelled) return;
            cancelled = true;
            if (future != null) future.cancel(true);
            closeQuietly(stream);
            cleanup();
        }

        private void cleanup() {
            operations.remove(this);
            closeQuietly(stream);
            stream = null;
            closeQuietly(registration);
            registration = null;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final int maximum;
        private int read;

        BoundedInputStream(InputStream input, int maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) counted(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int allowed = Math.min(length, maximum - read + 1);
            if (allowed <= 0) throw new ResponseTooLargeException(maximum);
            int count = super.read(bytes, offset, allowed);
            if (count > 0) counted(count);
            return count;
        }

        private void counted(int count) {
            read += count;
            if (read > maximum) throw new ResponseTooLargeException(maximum);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) { }
    }
}

