package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ChatPersistenceService.Run;
import com.learnhub.assistant.chat.application.ChatToolExecutionStore;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

/** 随机独立 schema，复用 verify-chat.ps1 的测试容器凭据，不连接业务 schema。 */
@EnabledIfSystemProperty(named = "learnhub.chat.it.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatToolExecutionInfrastructureIntegrationTest {
    private String database;
    private String serverUrl;
    private String username;
    private String password;
    private JdbcTemplate jdbc;
    private ChatPersistenceService persistence;
    private ChatToolExecutionStore tools;

    @BeforeAll
    void initializeOwnedSchema() throws Exception {
        database = "learnhub_chat_tool_it_" + UUID.randomUUID().toString().replace("-", "");
        serverUrl = "jdbc:mysql://" + environment("LEARNHUB_CHAT_IT_MYSQL_HOST", "localhost") + ":"
                + environment("LEARNHUB_CHAT_IT_MYSQL_PORT", "3306")
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        username = environment("LEARNHUB_CHAT_IT_MYSQL_ADMIN_USERNAME", "learnhub");
        password = environment("LEARNHUB_CHAT_IT_MYSQL_ADMIN_PASSWORD", "learnhub");
        try {
            try (var connection = DriverManager.getConnection(serverUrl, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            var source = new DriverManagerDataSource(serverUrl.replace("/?", "/" + database + "?"), username, password);
            Path schema = List.of(Path.of("sql/schema.sql"), Path.of("../sql/schema.sql")).stream()
                    .filter(Files::isRegularFile).findFirst().orElseThrow();
            String original = Files.readString(schema);
            String isolated = original.replaceFirst("(?im)^CREATE DATABASE IF NOT EXISTS learnhub[^;]*;\\s*", "")
                    .replaceFirst("(?im)^USE learnhub;\\s*", "");
            if (isolated.equals(original) || isolated.matches("(?is).*\\b(?:CREATE\\s+DATABASE[^;]*|USE)\\s+learnhub\\b.*"))
                throw new IllegalStateException("拒绝执行非隔离 schema");
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new ByteArrayResource(isolated.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }
            jdbc = new JdbcTemplate(source);
            persistence = new ChatPersistenceService(jdbc, new DataSourceTransactionManager(source));
            tools = new ChatToolExecutionStore(jdbc);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        } catch (Exception setupFailure) {
            try { dropOwnedSchema(); } catch (Exception cleanup) { setupFailure.addSuppressed(cleanup); }
            throw setupFailure;
        }
    }

    @BeforeEach
    void cleanOwnedChatTables() {
        for (String table : List.of("assistant_tool_execution", "assistant_message_source", "assistant_message",
                "assistant_run", "assistant_conversation")) jdbc.update("DELETE FROM " + table);
    }

    @AfterAll
    void dropOwnedSchema() throws Exception {
        if (database == null || !database.matches("learnhub_chat_tool_it_[a-f0-9]{32}"))
            throw new IllegalStateException("拒绝删除非工具测试 schema");
        try (var connection = DriverManager.getConnection(serverUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void persistsOnlyHashSafeCompletionAndDurationWithoutChangingRun() {
        Run run = started();
        String id = begin(run, "call-1", 1);
        tools.complete(7, run, id, 23);
        assertThat(state(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT result_summary FROM assistant_tool_execution WHERE id = ?", String.class, id))
                .isEqualTo("只读工具已完成");
        assertThat(jdbc.queryForObject("SELECT arguments_hash FROM assistant_tool_execution WHERE id = ?", String.class, id))
                .isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT duration_ms FROM assistant_tool_execution WHERE id = ?", Long.class, id))
                .isEqualTo(23L);
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("RUNNING");
        cancelled(() -> tools.fail(7, run, id, 24, "TOOL_FAILED", false));
        assertThat(state(id)).isEqualTo("COMPLETED");
    }

    @Test
    void duplicateOrderAndCallIdNeverCreateSecondExecution() {
        Run run = started();
        begin(run, "call-1", 1);
        cancelled(() -> begin(run, "call-1", 2));
        cancelled(() -> begin(run, "call-2", 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_tool_execution", Integer.class)).isEqualTo(1);
    }

    @Test
    void rechecksOwnerAndDatabaseLeaseForInsertAndCompletion() {
        Run run = started();
        String id = begin(run, "call-1", 1);
        Run forgedOwner = new Run(run.id(), run.conversationId(), 8, run.clientRequestId(), "RUNNING", run.leaseOwner(), "");
        cancelled(() -> tools.begin(8, forgedOwner, 2, call("call-2"), "a".repeat(64)));
        cancelled(() -> tools.complete(8, forgedOwner, id, 1));
        jdbc.update("UPDATE assistant_run SET lease_owner = 'replacement-lease' WHERE id = ?", run.id());
        cancelled(() -> begin(run, "call-2", 2));
        cancelled(() -> tools.complete(7, run, id, 1));
        cancelled(() -> tools.fail(7, run, id, 1, "TOOL_FAILED", false));
        assertThat(state(id)).isEqualTo("RUNNING");
    }

    @Test
    void expiredLeaseRejectsSuccessAndRecordsCancellation() {
        Run run = started();
        String id = begin(run, "call-1", 1);
        jdbc.update("UPDATE assistant_run SET lease_expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3)) WHERE id = ?", run.id());
        cancelled(() -> begin(run, "call-2", 2));
        cancelled(() -> tools.complete(7, run, id, 1));
        tools.fail(7, run, id, 1, "ASSISTANT_TIMEOUT", false);
        assertThat(state(id)).isEqualTo("CANCELLED");
    }

    @Test
    void deletedConversationRejectsLateSuccessAndCancelsTool() {
        Run run = started();
        String id = begin(run, "call-1", 1);
        persistence.delete(7, run.conversationId());
        cancelled(() -> begin(run, "call-2", 2));
        cancelled(() -> tools.complete(7, run, id, 1));
        tools.fail(7, run, id, 1, "CONVERSATION_DELETED", false);
        assertThat(state(id)).isEqualTo("CANCELLED");
    }

    @Test
    void safeFailureAndExplicitCancellationAreTerminalWithoutOverwritingRun() {
        Run run = started();
        String failed = begin(run, "call-1", 1);
        tools.fail(7, run, failed, 3, "TOOL_UNAVAILABLE", false);
        assertThat(state(failed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM assistant_tool_execution WHERE id = ?", String.class, failed))
                .isEqualTo("TOOL_UNAVAILABLE");
        String cancelled = begin(run, "call-2", 2);
        tools.fail(7, run, cancelled, 5, "ASSISTANT_CANCELLED", true);
        assertThat(state(cancelled)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("RUNNING");
    }

    @Test
    void completedRunMakesLateToolFailureCancelled() {
        Run run = started();
        String id = begin(run, "call-1", 1);
        assertThat(persistence.complete(7, run.id(), run.leaseOwner(), "完成", "[]", "{}")).isTrue();
        tools.fail(7, run, id, 1, "TOOL_FAILED", false);
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void recoveryCancelsOnlyTerminatedOrExpiredRunsAndIsIdempotent() {
        Run terminated = started();
        String abandoned = begin(terminated, "abandoned", 1);
        String completed = begin(terminated, "completed", 2);
        tools.complete(7, terminated, completed, 1);
        persistence.cancel(7, terminated.id());
        Run expired = started();
        String expiredTool = begin(expired, "expired", 1);
        mysqlExpire(expired);
        Run deleted = started();
        String deletedTool = begin(deleted, "deleted", 1);
        // 独立验证删除条件，不依赖 delete() 同步终止 run。
        jdbc.update("UPDATE assistant_conversation SET deleted = 1 WHERE id = ?", deleted.conversationId());
        Run active = started();
        String activeTool = begin(active, "active", 1);

        assertThat(tools.recoverTerminated()).isEqualTo(3);
        assertThat(tools.recoverTerminated()).isZero();
        for (String id : List.of(abandoned, expiredTool, deletedTool)) {
            assertThat(state(id)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT error_code FROM assistant_tool_execution WHERE id = ?", String.class, id))
                    .isEqualTo("TOOL_RUN_TERMINATED");
        }
        assertThat(state(completed)).isEqualTo("COMPLETED");
        assertThat(state(activeTool)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, expired.id()))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_run WHERE id = ?", String.class, active.id()))
                .isEqualTo("RUNNING");
    }

    private void mysqlExpire(Run run) {
        jdbc.update("UPDATE assistant_run SET lease_expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3)) WHERE id = ?", run.id());
    }

    private Run started() {
        var conversation = persistence.createConversation(7, 10, "工具测试");
        return persistence.start(7, conversation.id(), UUID.randomUUID().toString(), "测试问题", Duration.ofSeconds(30)).run();
    }
    private String begin(Run run, String callId, int order) {
        return tools.begin(7, run, order, call(callId), "a".repeat(64));
    }
    private ToolCall call(String id) { return new ToolCall(id, "get_course_outline", "{\"private\":\"never-persist\"}"); }
    private String state(String id) {
        return jdbc.queryForObject("SELECT status FROM assistant_tool_execution WHERE id = ?", String.class, id);
    }
    private void cancelled(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
    }
    private String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }
}
