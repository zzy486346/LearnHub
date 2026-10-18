package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "learnhub.chat.it.enabled", matches = "true")
class ChatPersistenceIntegrationTest {
    private static final String DATABASE_PREFIX = "learnhub_chat_it_";
    private static final String ADMIN_USERNAME_ENV = "LEARNHUB_CHAT_IT_MYSQL_ADMIN_USERNAME";
    private static final String ADMIN_PASSWORD_ENV = "LEARNHUB_CHAT_IT_MYSQL_ADMIN_PASSWORD";
    private static final long OWNER_A = 41001L;
    private static final long OWNER_B = 41002L;
    private static final long COURSE_ID = 42001L;

    private String databaseName;
    private String serverUrl;
    private DatabaseCredentials credentials;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ChatPersistenceService service;

    @BeforeAll
    void createIsolatedDatabase() throws Exception {
        databaseName = DATABASE_PREFIX + randomToken();
        serverUrl = mysqlServerUrl();
        credentials = databaseCredentials();
        try {
            createDatabase();
            dataSource = isolatedDataSource();
            initializeSchema();
            jdbc = new JdbcTemplate(dataSource);
            PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            transactions = new TransactionTemplate(transactionManager);
            service = new ChatPersistenceService(jdbc, transactionManager);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(databaseName);
        } catch (Exception error) {
            dropDatabaseAfterFailure(error);
            throw error;
        }
    }

    @BeforeEach
    void cleanChatTables() {
        jdbc.update("DELETE FROM assistant_tool_execution");
        jdbc.update("DELETE FROM assistant_message_source");
        jdbc.update("DELETE FROM assistant_message");
        jdbc.update("DELETE FROM assistant_run");
        jdbc.update("DELETE FROM assistant_conversation");
    }

    @AfterAll
    void dropIsolatedDatabase() throws SQLException {
        dropDatabase();
    }

    @Test
    void schemaHasFiveOwnedTablesCommentsAndRequiredUniqueKeys() {
        var tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'assistant_%'
                ORDER BY TABLE_NAME
                """, String.class);
        assertThat(tables).containsExactly(
                "assistant_conversation", "assistant_message", "assistant_message_source",
                "assistant_run", "assistant_tool_execution");

        Integer uncommentedTables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'assistant_%'
                  AND TRIM(TABLE_COMMENT) = ''
                """, Integer.class);
        Integer uncommentedColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'assistant_%'
                  AND TRIM(COLUMN_COMMENT) = ''
                """, Integer.class);
        assertThat(uncommentedTables).isZero();
        assertThat(uncommentedColumns).isZero();
        assertThat(showCreateTable("assistant_run"))
                .contains("UNIQUE KEY")
                .contains("client_request_id");
        assertThat(showCreateTable("assistant_message"))
                .contains("UNIQUE KEY")
                .contains("conversation_id")
                .contains("seq");
    }

    @Test
    void conversationsAreOwnerScopedListedRenamedAndSoftDeleted() {
        var first = service.createConversation(OWNER_A, COURSE_ID, "第一节");
        var second = service.createConversation(OWNER_A, COURSE_ID, "第二节");
        service.createConversation(OWNER_B, COURSE_ID, "其他用户");

        assertThat(service.conversations(OWNER_A, 0, 10))
                .extracting(ChatPersistenceService.Conversation::id)
                .containsExactlyInAnyOrder(second.id(), first.id());
        String expectedSecondPageId = jdbc.queryForObject("""
                SELECT id FROM assistant_conversation
                WHERE owner_user_id = ? AND deleted = 0
                ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 1
                """, String.class, OWNER_A);
        assertThat(service.conversations(OWNER_A, 1, 1))
                .extracting(ChatPersistenceService.Conversation::id)
                .containsExactly(expectedSecondPageId);
        service.rename(OWNER_A, first.id(), "已改名");
        assertThat(service.conversation(OWNER_A, first.id()).title()).isEqualTo("已改名");

        assertBusinessCode("CHAT_NOT_FOUND", () -> service.conversation(OWNER_B, first.id()));
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.rename(OWNER_B, first.id(), "越权"));
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.messages(OWNER_B, first.id(), Long.MAX_VALUE, 20));

        assertThat(service.delete(OWNER_A, first.id())).isEmpty();
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.conversation(OWNER_A, first.id()));
        assertThat(jdbc.queryForObject("SELECT deleted FROM assistant_conversation WHERE id = ?",
                Integer.class, first.id())).isEqualTo(1);
    }

    @Test
    void startIsIdempotentAndRejectsChangedPayloadForSameClientRequest() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "幂等");
        var first = service.start(OWNER_A, conversation.id(), "client-1", "什么是缓存穿透？", Duration.ofSeconds(30));
        var repeated = service.start(OWNER_A, conversation.id(), "client-1", "什么是缓存穿透？", Duration.ofSeconds(30));

        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.run().id()).isEqualTo(first.run().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message", Integer.class)).isEqualTo(1);
        assertBusinessCode("CHAT_IDEMPOTENCY_CONFLICT",
                () -> service.start(OWNER_A, conversation.id(), "client-1", "改过的问题", Duration.ofSeconds(30)));
        var otherConversation = service.createConversation(OWNER_A, COURSE_ID, "另一个会话");
        assertBusinessCode("CHAT_IDEMPOTENCY_CONFLICT",
                () -> service.start(OWNER_A, otherConversation.id(), "client-1", "什么是缓存穿透？",
                        Duration.ofSeconds(30)));
        assertBusinessCode("CHAT_NOT_FOUND",
                () -> service.start(OWNER_B, conversation.id(), "client-b", "越权", Duration.ofSeconds(30)));
    }

    @Test
    void concurrentCrossConversationReuseOfClientRequestCreatesOnlyOneRun() throws Exception {
        var firstConversation = service.createConversation(OWNER_A, COURSE_ID, "跨会话一");
        var secondConversation = service.createConversation(OWNER_A, COURSE_ID, "跨会话二");
        var ready = new CyclicBarrier(2);
        Callable<StartAttempt> firstTask = () -> concurrentStart(ready, firstConversation.id(), "并发参数一");
        Callable<StartAttempt> secondTask = () -> concurrentStart(ready, secondConversation.id(), "并发参数二");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(firstTask);
            var secondFuture = executor.submit(secondTask);
            var attempts = List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
            assertThat(attempts.stream().filter(result -> result.started() != null).count()).isEqualTo(1);
            assertThat(attempts.stream().filter(result -> result.error() != null)
                    .map(result -> result.error().getCode())).containsExactly("CHAT_IDEMPOTENCY_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_run", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentStartsAllowOnlyOneRunningRunPerConversation() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "并发");
        var ready = new CyclicBarrier(2);
        Callable<StartAttempt> task = () -> {
            ready.await(10, TimeUnit.SECONDS);
            try {
                var started = service.start(OWNER_A, conversation.id(), "client-" + randomToken(),
                        "并发问题", Duration.ofSeconds(30));
                return new StartAttempt(started, null);
            } catch (BusinessException error) {
                return new StartAttempt(null, error);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(task);
            var secondFuture = executor.submit(task);
            var first = firstFuture.get(15, TimeUnit.SECONDS);
            var second = secondFuture.get(15, TimeUnit.SECONDS);
            assertThat(List.of(first, second).stream().filter(result -> result.started() != null).count()).isEqualTo(1);
            assertThat(List.of(first, second).stream().filter(result -> result.error() != null)
                    .map(result -> result.error().getCode())).containsExactly("CHAT_BUSY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_run WHERE status = 'RUNNING'", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void completionRequiresLeaseOwnerAndPersistsOnlyCompletedAssistantMessage() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "完成");
        var started = service.start(OWNER_A, conversation.id(), "client-complete", "解释缓存击穿", Duration.ofSeconds(30));
        var source = new KnowledgeRetrievalService.Source(
                "9", 3, "11", Long.toString(COURSE_ID), "43001", "缓存课程", "缓存击穿", 2,
                "使用互斥锁并设置过期时间。", 1.0);
        var objectMapper = new ObjectMapper();
        String sourcesJson = objectMapper.writeValueAsString(List.of(source));

        assertThat(service.complete(OWNER_A, started.run().id(), "wrong-lease", "错误答案", "[]", "{}"))
                .isFalse();
        assertThat(service.messages(OWNER_A, conversation.id(), Long.MAX_VALUE, 10))
                .extracting(ChatPersistenceService.Message::role)
                .containsExactly("USER");

        assertThat(service.complete(OWNER_A, started.run().id(), started.run().leaseOwner(),
                "使用互斥锁并设置过期时间。", sourcesJson, "{\"totalTokens\":12}"))
                .isTrue();
        assertThat(service.complete(OWNER_A, started.run().id(), started.run().leaseOwner(),
                "重复完成", "[]", "{}"))
                .isFalse();
        assertThat(service.run(OWNER_A, started.run().id()).status()).isEqualTo("COMPLETED");
        var messages = service.messages(OWNER_A, conversation.id(), Long.MAX_VALUE, 10);
        assertThat(messages)
                .extracting(ChatPersistenceService.Message::role,
                        ChatPersistenceService.Message::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("USER", "COMPLETED"),
                        org.assertj.core.groups.Tuple.tuple("ASSISTANT", "COMPLETED"));
        assertThat(objectMapper.readTree(messages.get(1).sourcesJson())).isEqualTo(objectMapper.readTree(sourcesJson));
        assertThat(jdbc.queryForMap("""
                SELECT document_id, chunk_id, material_version, source_kind, citation_id
                FROM assistant_message_source
                """))
                .containsEntry("document_id", 9L)
                .containsEntry("chunk_id", 11L)
                .containsEntry("material_version", 3L)
                .containsEntry("source_kind", "MATERIAL")
                .containsEntry("citation_id", "E1");
    }

    @Test
    void messagePaginationReturnsLatestRowsBeforeCursorInAscendingOrder() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "分页");
        for (int index = 1; index <= 3; index++) {
            var started = service.start(OWNER_A, conversation.id(), "page-" + index, "问题" + index,
                    Duration.ofSeconds(30));
            assertThat(service.complete(OWNER_A, started.run().id(), started.run().leaseOwner(),
                    "回答" + index, "[]", "{}"))
                    .isTrue();
        }

        assertThat(service.messages(OWNER_A, conversation.id(), Long.MAX_VALUE, 2))
                .extracting(ChatPersistenceService.Message::seq)
                .containsExactly(5L, 6L);
        assertThat(service.messages(OWNER_A, conversation.id(), 5L, 3))
                .extracting(ChatPersistenceService.Message::seq)
                .containsExactly(2L, 3L, 4L);
        assertThat(service.conversation(OWNER_A, conversation.id()).nextSeq()).isEqualTo(7L);
    }

    @Test
    void cancelAndFailAreOwnerScopedTerminalTransitions() {
        var cancelledConversation = service.createConversation(OWNER_A, COURSE_ID, "取消");
        var cancelled = service.start(OWNER_A, cancelledConversation.id(), "cancel-1", "停止生成",
                Duration.ofSeconds(30));
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.cancel(OWNER_B, cancelled.run().id()));
        assertThat(service.cancel(OWNER_A, cancelled.run().id()).status()).isEqualTo("CANCELLED");
        assertThat(service.cancel(OWNER_A, cancelled.run().id()).status()).isEqualTo("CANCELLED");
        assertThat(service.complete(OWNER_A, cancelled.run().id(), cancelled.run().leaseOwner(),
                "迟到答案", "[]", "{}"))
                .isFalse();

        var failedConversation = service.createConversation(OWNER_A, COURSE_ID, "失败");
        var failed = service.start(OWNER_A, failedConversation.id(), "fail-1", "触发失败", Duration.ofSeconds(30));
        assertThat(service.fail(OWNER_A, failed.run().id(), "FAILED", "MODEL_UNAVAILABLE")).isTrue();
        assertThat(service.fail(OWNER_A, failed.run().id(), "FAILED", "MODEL_UNAVAILABLE")).isFalse();
        assertThat(service.run(OWNER_A, failed.run().id()).status()).isEqualTo("FAILED");
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.run(OWNER_B, failed.run().id()));
    }

    @Test
    void expiredLeaseRecoveryInterruptsOnlyStaleRunningRunsAndReleasesConversation() {
        var staleConversation = service.createConversation(OWNER_A, COURSE_ID, "崩溃恢复");
        var stale = service.start(OWNER_A, staleConversation.id(), "stale-1", "旧运行", Duration.ofSeconds(30));
        var healthyConversation = service.createConversation(OWNER_A, COURSE_ID, "健康运行");
        var healthy = service.start(OWNER_A, healthyConversation.id(), "healthy-1", "新运行", Duration.ofMinutes(5));

        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE assistant_run SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?",
                stale.run().id()));
        assertThat(service.recoverExpired()).isEqualTo(1);
        assertThat(service.run(OWNER_A, stale.run().id()).status()).isEqualTo("INTERRUPTED");
        assertThat(service.complete(OWNER_A, stale.run().id(), stale.run().leaseOwner(),
                "失租后的迟到答案", "[]", "{}"))
                .isFalse();
        assertThat(service.run(OWNER_A, healthy.run().id()).status()).isEqualTo("RUNNING");
        assertThat(service.start(OWNER_A, staleConversation.id(), "after-recovery", "继续追问", Duration.ofSeconds(30))
                .created()).isTrue();
    }

    @Test
    void deletingConversationCancelsItsActiveRunAndReturnsRunId() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "删除");
        var started = service.start(OWNER_A, conversation.id(), "delete-1", "尚未完成", Duration.ofSeconds(30));

        assertThat(service.delete(OWNER_A, conversation.id())).containsExactly(started.run().id());
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, started.run().id()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT deleted FROM assistant_conversation WHERE id = ?",
                Integer.class, conversation.id())).isEqualTo(1);
        assertThat(service.complete(OWNER_A, started.run().id(), started.run().leaseOwner(),
                "删除后的迟到答案", "[]", "{}"))
                .isFalse();
        assertBusinessCode("CHAT_NOT_FOUND", () -> service.delete(OWNER_B, conversation.id()));
    }

    private void initializeSchema() throws Exception {
        executeIsolatedSql(schemaPath(), true, "isolated copy of sql/schema.sql");
        executeIsolatedSql(migrationPath(), false, "isolated copy of assistant chat migration");
        executeIsolatedSql(migrationPath(), false, "second isolated copy of assistant chat migration");
    }

    private void executeIsolatedSql(Path path, boolean schema, String description) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        String isolated = sql
                .replaceFirst("(?im)^CREATE DATABASE IF NOT EXISTS learnhub[^;]*;\\s*", "")
                .replaceFirst("(?im)^USE learnhub;\\s*", "");
        if ((schema && isolated.equals(sql)) || isolated.matches("(?is).*\\b(?:CREATE\\s+DATABASE[^;]*|USE)\\s+learnhub\\b.*")) {
            throw new IllegalStateException("SQL 初始化前导发生变化，拒绝在非隔离数据库执行: " + path);
        }
        try (Connection connection = dataSource.getConnection()) {
            var resource = new ByteArrayResource(isolated.getBytes(StandardCharsets.UTF_8), description);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private String showCreateTable(String tableName) {
        return jdbc.queryForObject("SHOW CREATE TABLE " + tableName, (result, row) -> result.getString(2));
    }

    private void createDatabase() {
        try (Connection connection = DriverManager.getConnection(serverUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + databaseName
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException error) {
            throw new IllegalStateException("无法创建隔离聊天测试数据库；请通过 " + ADMIN_USERNAME_ENV + " 和 "
                    + ADMIN_PASSWORD_ENV + " 提供测试管理员凭据（SQLState=" + error.getSQLState()
                    + "，vendorCode=" + error.getErrorCode() + "）", error);
        }
    }

    private DataSource isolatedDataSource() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(serverUrl.replace("/?", "/" + databaseName + "?"));
        source.setUsername(credentials.username());
        source.setPassword(credentials.password());
        return source;
    }

    private void dropDatabaseAfterFailure(Exception setupFailure) {
        try {
            dropDatabase();
        } catch (Exception cleanupFailure) {
            setupFailure.addSuppressed(cleanupFailure);
        }
    }

    private void dropDatabase() throws SQLException {
        if (databaseName == null || !databaseName.matches("learnhub_chat_it_[a-f0-9]{24}")) {
            throw new IllegalStateException("拒绝删除非聊天集成测试数据库");
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    private DatabaseCredentials databaseCredentials() {
        String username = trimToNull(System.getenv(ADMIN_USERNAME_ENV));
        String password = System.getenv(ADMIN_PASSWORD_ENV);
        if ((username == null) != (password == null)) {
            throw new IllegalStateException(ADMIN_USERNAME_ENV + " 与 " + ADMIN_PASSWORD_ENV + " 必须同时设置");
        }
        if (username != null) return new DatabaseCredentials(username, password);
        return new DatabaseCredentials("learnhub", "learnhub");
    }

    private String mysqlServerUrl() {
        return "jdbc:mysql://" + environment("LEARNHUB_CHAT_IT_MYSQL_HOST", "localhost") + ":"
                + environment("LEARNHUB_CHAT_IT_MYSQL_PORT", "3306")
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    private Path schemaPath() {
        return findProjectFile(Path.of("sql", "schema.sql"));
    }

    private Path migrationPath() {
        return findProjectFile(Path.of("sql", "migrations", "20261018_assistant_chat.sql"));
    }

    private Path findProjectFile(Path relative) {
        for (Path candidate : List.of(relative, Path.of("..").resolve(relative))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return normalized;
        }
        throw new IllegalStateException("找不到项目文件: " + relative);
    }

    private void assertBusinessCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private StartAttempt concurrentStart(CyclicBarrier ready, String conversationId, String message) throws Exception {
        ready.await(10, TimeUnit.SECONDS);
        try {
            return new StartAttempt(service.start(OWNER_A, conversationId, "shared-client-request", message,
                    Duration.ofSeconds(30)), null);
        } catch (BusinessException error) {
            return new StartAttempt(null, error);
        }
    }

    private String environment(String name, String fallback) {
        String value = trimToNull(System.getenv(name));
        return value == null ? fallback : value;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private record DatabaseCredentials(String username, String password) {}

    private record StartAttempt(ChatPersistenceService.Started started, BusinessException error) {}
}
