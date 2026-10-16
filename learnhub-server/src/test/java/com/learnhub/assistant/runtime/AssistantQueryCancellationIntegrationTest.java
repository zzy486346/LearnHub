package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 只执行本连接的 SLEEP，不修改业务数据、不结束其他连接。 */
@EnabledIfSystemProperty(named = "learnhub.assistant.io.it.enabled", matches = "true")
class AssistantQueryCancellationIntegrationTest {
    @Test @Timeout(20) void cancellationInterruptsRealMysqlQuery() throws Exception {
        Properties local = new Properties();
        Path env = Files.isRegularFile(Path.of(".env")) ? Path.of(".env") : Path.of("../.env");
        try (var reader = Files.newBufferedReader(env)) { local.load(reader); }
        String url = value(local, "LEARNHUB_DB_URL", "jdbc:mysql://localhost:3306/learnhub?serverTimezone=Asia/Shanghai");
        String user = value(local, "LEARNHUB_DB_USERNAME", "learnhub");
        String password = value(local, "LEARNHUB_DB_PASSWORD", "learnhub");
        var scope = new AssistantIoScope(Duration.ofSeconds(10));
        var executor = Executors.newSingleThreadExecutor();
        var connectionId = new AtomicLong();
        var ready = new CountDownLatch(1);
        long started = System.nanoTime();
        try {
            Future<Boolean> task = executor.submit(() -> {
                try (scope; var connection = DriverManager.getConnection(url, user, password);
                        var statement = connection.createStatement()) {
                    scope.attach(); scope.track(statement);
                    try (var id = statement.executeQuery("SELECT CONNECTION_ID()")) { id.next(); connectionId.set(id.getLong(1)); }
                    ready.countDown();
                    try { statement.executeQuery("SELECT SLEEP(15) /* learnhub-assistant-cancel-test */"); return false; }
                    catch (java.sql.SQLException cancelled) { return true; }
                }
            });
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            try (var observer = DriverManager.getConnection(url, user, password);
                    var query = observer.prepareStatement("SELECT INFO FROM information_schema.PROCESSLIST WHERE ID = ?")) {
                query.setLong(1, connectionId.get()); query.setQueryTimeout(2);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean executing = false;
                while (System.nanoTime() < until) {
                    try (var rows = query.executeQuery()) {
                        if (rows.next() && String.valueOf(rows.getString(1)).contains("learnhub-assistant-cancel-test")) {
                            executing = true; break;
                        }
                    }
                    Thread.sleep(20);
                }
                assertThat(executing).as("确认取消的是本次正在执行的查询").isTrue();
            }
            scope.cancel();
            assertThat(task.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        } finally { scope.cancel(); executor.shutdownNow(); }
    }
    private static String value(Properties local, String key, String fallback) {
        String process = System.getenv(key);
        return process == null || process.isBlank() ? local.getProperty(key, fallback) : process;
    }
}
