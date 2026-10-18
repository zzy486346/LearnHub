package com.learnhub.experiments.transport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundedJdkHttpClientCancellationTest {
    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void cancelsBeforeFirstByteWithoutAnyLateCallback() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: too-late\n\n")
                .setBodyDelay(2, TimeUnit.SECONDS));
        CancellationHarness harness = new CancellationHarness();
        RecordingListener listener = new RecordingListener();

        try (BoundedJdkHttpClientBuilder builder = builder(harness)) {
            HttpClient client = builder.build();
            client.execute(request(), new DefaultServerSentEventParser(), listener);
            assertThat(harness.registered.await(1, TimeUnit.SECONDS)).isTrue();

            harness.cancel();
            Thread.sleep(2_300);

            assertThat(listener.eventValues()).isEmpty();
            assertThat(listener.callbackCount()).isZero();
            assertThat(harness.active.get()).isZero();
        }
    }

    @Test
    void cancelsSynchronousReadWhileBodyIsPausedAfterHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("response-body")
                .setBodyDelay(3, TimeUnit.SECONDS));
        CancellationHarness harness = new CancellationHarness();

        try (BoundedJdkHttpClientBuilder builder = builder(harness)) {
            HttpClient client = builder.build();
            CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
                try {
                    client.execute(request());
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(harness.registered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            Thread.sleep(200);

            harness.cancel();

            assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            assertThat(harness.active.get()).isZero();
        }
    }

    @Test
    void cancelsAfterFirstEventAndCancelReturnSerializesAgainstCallbacks() throws Exception {
        String first = "data: first\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(first + "data: second\n\n")
                .throttleBody(first.getBytes().length, 1, TimeUnit.SECONDS));
        CancellationHarness harness = new CancellationHarness();
        RecordingListener listener = new RecordingListener(true);

        try (BoundedJdkHttpClientBuilder builder = builder(harness)) {
            builder.build().execute(request(), new DefaultServerSentEventParser(), listener);
            assertThat(listener.firstEvent.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch cancelReturned = new CountDownLatch(1);
            Thread cancelThread = new Thread(() -> {
                harness.cancel();
                cancelReturned.countDown();
            }, "transport-cancel-test");
            cancelThread.start();
            assertThat(cancelReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            listener.releaseFirstEvent.countDown();
            assertThat(cancelReturned.await(2, TimeUnit.SECONDS)).isTrue();
            int callbacksWhenCancelReturned = listener.callbackCount();
            Thread.sleep(1_500);

            assertThat(listener.eventValues()).containsExactly("first");
            assertThat(listener.callbackCount()).isEqualTo(callbacksWhenCancelReturned);
            assertThat(listener.closed.get()).isZero();
            assertThat(listener.error.get()).isNull();
            assertThat(harness.active.get()).isZero();
        }
    }

    @Test
    void rejectsOversizedErrorBodyAtTheSharedLimit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("x".repeat(262_145)));
        CancellationHarness harness = new CancellationHarness();
        RecordingListener listener = new RecordingListener();

        try (BoundedJdkHttpClientBuilder builder = builder(harness)) {
            builder.build().execute(request(), new DefaultServerSentEventParser(), listener);

            assertThat(listener.terminal.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.error.get()).isInstanceOf(ResponseTooLargeException.class);
            assertThat(listener.closed.get()).isZero();
            assertThat(harness.active.get()).isZero();
        }
    }

    @Test
    void rejectsOversizedSuccessfulSseAtTheSharedLimit() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + "x".repeat(262_145) + "\n\n"));
        CancellationHarness harness = new CancellationHarness();
        RecordingListener listener = new RecordingListener();

        try (BoundedJdkHttpClientBuilder builder = builder(harness)) {
            builder.build().execute(request(), new DefaultServerSentEventParser(), listener);

            assertThat(listener.terminal.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.error.get()).isInstanceOf(ResponseTooLargeException.class);
            assertThat(listener.eventValues()).isEmpty();
            assertThat(harness.active.get()).isZero();
        }
    }

    @Test
    void explicitCloseTerminatesOwnedThreadsAndCleansRegistration() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: done\n\n"));
        Set<String> threadsBefore = transportThreads();
        CancellationHarness harness = new CancellationHarness();
        RecordingListener listener = new RecordingListener();

        BoundedJdkHttpClientBuilder builder = builder(harness);
        builder.build().execute(request(), new DefaultServerSentEventParser(), listener);
        assertThat(listener.terminal.await(3, TimeUnit.SECONDS)).isTrue();
        builder.close();

        assertThat(harness.active.get()).isZero();
        assertThat(transportThreads()).isSubsetOf(threadsBefore);
    }

    private BoundedJdkHttpClientBuilder builder(CancellationHarness harness) {
        return new BoundedJdkHttpClientBuilder(harness::register, harness::cleanup)
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5));
    }

    private HttpRequest request() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(server.url("/v1/chat/completions").toString())
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build();
    }

    private static Set<String> transportThreads() {
        Set<String> names = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("lc4j-bounded-http-")) names.add(thread.getName());
        }
        return names;
    }

    private static final class CancellationHarness {
        private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
        private final AtomicInteger active = new AtomicInteger();
        private final CountDownLatch registered = new CountDownLatch(1);

        void register(Runnable action) {
            cancellation.set(action);
            active.incrementAndGet();
            registered.countDown();
        }

        AutoCloseable cleanup() {
            return () -> {
                cancellation.set(null);
                active.decrementAndGet();
            };
        }

        void cancel() {
            Runnable action = cancellation.get();
            assertThat(action).isNotNull();
            action.run();
        }
    }

    private static final class RecordingListener implements ServerSentEventListener {
        private final List<String> events = new ArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final CountDownLatch firstEvent = new CountDownLatch(1);
        private final CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final boolean blockFirstEvent;

        RecordingListener() {
            this(false);
        }

        RecordingListener(boolean blockFirstEvent) {
            this.blockFirstEvent = blockFirstEvent;
        }

        @Override
        public synchronized void onOpen(SuccessfulHttpResponse response) {
            opened.incrementAndGet();
        }

        @Override
        public synchronized void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            events.add(event.data());
            firstEvent.countDown();
            if (blockFirstEvent && events.size() == 1) {
                try {
                    releaseFirstEvent.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public synchronized void onError(Throwable failure) {
            error.set(failure);
            terminal.countDown();
        }

        @Override
        public synchronized void onClose() {
            closed.incrementAndGet();
            terminal.countDown();
        }

        synchronized int callbackCount() {
            return opened.get() + events.size() + closed.get() + (error.get() == null ? 0 : 1);
        }

        synchronized List<String> eventValues() {
            return List.copyOf(events);
        }
    }
}
