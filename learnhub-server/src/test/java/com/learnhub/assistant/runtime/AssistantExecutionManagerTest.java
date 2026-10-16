package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AssistantExecutionManagerTest {
    @Test void explicitCancellationInterruptsOnlyOwnedRunAndReleasesIt() throws Exception {
        var guard = mock(AssistantRunGuard.class);
        var run = mock(AssistantRunGuard.Run.class);
        when(guard.open(anyLong(), anyString(), any())).thenReturn(run);
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var id = UUID.randomUUID().toString();
        try (var manager = new ManagerResource(new AssistantExecutionManager(guard, new AssistantLimits()))) {
            var result = manager.value.submit(7L, id, () -> {}, ignored -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { stopped.countDown(); }
                return "cancelled";
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.value.cancel(8L, id)).isFalse();
            assertThat(stopped.getCount()).isEqualTo(1);
            assertThat(manager.value.cancel(7L, id)).isTrue();
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.hasResult()).isTrue();
            verify(run, timeout(1000)).close();
        }
    }

    @Test void malformedRequestIdRejectedBeforeScheduling() {
        try (var manager = new ManagerResource(new AssistantExecutionManager(mock(AssistantRunGuard.class), new AssistantLimits()))) {
            assertThatThrownBy(() -> manager.value.submit(7L, "arbitrary/path", () -> {}, run -> "unused"))
                    .hasMessageContaining("UUID");
        }
    }

    private record ManagerResource(AssistantExecutionManager value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
