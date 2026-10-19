package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CourseVectorRuntimeTest {
    @Test void unavailablePgFailsWithinBoundAndPrivatePoolIsClosed() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var sync = mock(VectorProjectionSync.class);
        when(sync.currentVersions(10, List.of(101L))).thenReturn(Map.of(1L, 1L));
        var runtime = new CourseVectorRuntime(sync, true, false,
                "jdbc:postgresql://127.0.0.1:" + port + "/not_present", "test_app", "temporary-not-secret");
        com.zaxxer.hikari.HikariDataSource pool;
        try {
            assertThatThrownBy(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(8),
                    () -> runtime.search(10, List.of(101L), "课程", 5))).isInstanceOf(RuntimeException.class);
            pool = (com.zaxxer.hikari.HikariDataSource) ReflectionTestUtils.getField(runtime, "pool");
        } finally { runtime.close(); }
        assertThat(pool == null || pool.isClosed()).isTrue();
        assertThatThrownBy(() -> runtime.search(10, List.of(101L), "课程", 5))
                .isInstanceOf(IllegalStateException.class);
    }
}
