package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.elasticsearch.client.Cancellable;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

class AssistantIoScopeTest {
    @Test void cancellationReachesSqlAndScopeIsRemoved() throws Exception {
        Statement statement = mock(Statement.class);
        try (var scope = new AssistantIoScope(Duration.ofSeconds(2))) {
            scope.attach(); scope.track(statement); scope.cancel();
            verify(statement).setQueryTimeout(intThat(value -> value > 0 && value <= 2));
            verify(statement).cancel();
            assertThatThrownBy(scope::remaining).hasMessageContaining("取消");
        }
        assertThat(AssistantIoScope.current()).isNull();
    }

    @Test void cancellationReachesPendingElasticsearchRequest() throws Exception {
        RestClient client = mock(RestClient.class);
        Cancellable pending = mock(Cancellable.class);
        CountDownLatch sent = new CountDownLatch(1);
        when(client.performRequestAsync(any(), any())).thenAnswer(invocation -> { sent.countDown(); return pending; });
        var scope = new AssistantIoScope(Duration.ofSeconds(3));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> {
                try (scope) {
                    scope.attach(); AssistantSearchIo.perform(client, new Request("GET", "/test"));
                } catch (Throwable error) { failure.set(error); }
            });
            assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
            scope.cancel(); task.get(2, TimeUnit.SECONDS);
            verify(pending).cancel();
            assertThat(failure.get()).isInstanceOf(java.io.IOException.class);
        } finally { scope.cancel(); executor.shutdownNow(); }
    }

    @Test void nonAssistantSearchKeepsExistingSynchronousBehavior() throws Exception {
        RestClient client = mock(RestClient.class);
        Request request = new Request("GET", "/test");
        AssistantSearchIo.perform(client, request);
        verify(client).performRequest(request);
        verify(client, never()).performRequestAsync(any(), any());
    }
}
