package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ContextCheckpoint;
import com.learnhub.assistant.chat.application.ChatToolExecutionStore;
import com.learnhub.assistant.chat.application.ChatToolResultStore;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.assistant.chat.application.ChatEvidenceCodec;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
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
    private ChatToolExecutionStore toolExecutions;
    private ChatToolResultStore toolResults;
    private AssistantLimits limits;

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
            limits = new AssistantLimits(); limits.setContextRedesignEnabled(true);
            service = new ChatPersistenceService(jdbc, transactionManager, limits);
            toolExecutions = new ChatToolExecutionStore(jdbc);
            toolResults = new ChatToolResultStore(jdbc, transactionManager, limits);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(databaseName);
        } catch (Exception error) {
            dropDatabaseAfterFailure(error);
            throw error;
        }
    }

    @BeforeEach
    void cleanChatTables() {
        jdbc.update("DELETE FROM assistant_tool_result");
        jdbc.update("DELETE FROM assistant_context_checkpoint");
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
                "assistant_context_checkpoint", "assistant_conversation", "assistant_learning_preference",
                "assistant_message", "assistant_message_source", "assistant_preference_extraction_attempt",
                "assistant_run", "assistant_tool_execution", "assistant_tool_result");

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
        assertThat(showCreateTable("assistant_tool_result"))
                .contains("UNIQUE KEY", "execution_id", "expires_at", "COMMENT");
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
    void reservedUsageSurvivesFailureAndLateUsageCannotReplaceTerminalRecord() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "预留用量");
        var started = service.start(OWNER_A, conversation.id(), "usage-request", "缓存击穿", Duration.ofSeconds(30));
        service.recordUsage(OWNER_A, started.run().id(), started.run().leaseOwner(),
                "{\"reservedTokens\":2048,\"usageComplete\":false}");
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(usage_json, '$.reservedTokens') FROM assistant_run WHERE id = ?",
                Integer.class, started.run().id())).isEqualTo(2048);
        service.cancel(OWNER_A, started.run().id());
        assertBusinessCode("ASSISTANT_CANCELLED", () -> service.recordUsage(OWNER_A, started.run().id(),
                started.run().leaseOwner(), "{\"reservedTokens\":0}"));
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(usage_json, '$.reservedTokens') FROM assistant_run WHERE id = ?",
                Integer.class, started.run().id())).isEqualTo(2048);
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
    void dependencyEnvelopeRoundTripsAndOnlyMaterialKeepsItsActualEvidenceId() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "完整证据依赖");
        var started = service.start(OWNER_A, conversation.id(), "dependency-envelope", "继续解释课程目录",
                Duration.ofSeconds(30));
        var objectMapper = new ObjectMapper();
        var book = new EvidenceBook(OWNER_A, COURSE_ID,
                org.mockito.Mockito.mock(KnowledgeStore.class), org.mockito.Mockito.mock(CourseService.class), objectMapper);
        var outline = objectMapper.createObjectNode().put("courseId", Long.toString(COURSE_ID));
        outline.putArray("chapters");
        book.register("outline:" + COURSE_ID, "OUTLINE", outline, () -> outline.deepCopy());
        var source = new KnowledgeRetrievalService.Source("9", 3, "11", Long.toString(COURSE_ID),
                "43001", "缓存课程", "缓存击穿", 2, "使用互斥锁并设置过期时间。", 1.0);
        assertThat(book.material(source)).isEqualTo("E2");
        String envelope = new ChatEvidenceCodec(null).encode(book);
        assertThat(service.complete(OWNER_A, started.run().id(), started.run().leaseOwner(),
                "目录与材料共同支持的回答 [E1][E2]", envelope, "{}"))
                .isTrue();
        var messages = service.messages(OWNER_A, conversation.id(), Long.MAX_VALUE, 10);
        assertThat(objectMapper.readTree(messages.get(1).sourcesJson())).isEqualTo(objectMapper.readTree(envelope));
        assertThat(ChatEvidenceCodec.parse(messages.get(1).sourcesJson()))
                .extracting(ChatEvidenceCodec.ParsedEntry::kind).containsExactly("OUTLINE", "MATERIAL");
        assertThat(jdbc.queryForList("""
                SELECT document_id, chunk_id, material_version, source_kind, citation_id
                FROM assistant_message_source
                """)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("document_id", 9L).containsEntry("chunk_id", 11L)
                .containsEntry("material_version", 3L).containsEntry("source_kind", "MATERIAL")
                .containsEntry("citation_id", "E2"));
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

    @Test void toolResultWriteAndExecutionCompletionAreAtomicAndReadIsFullyScoped() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "工具结果");
        var run = service.start(OWNER_A, conversation.id(), "tool-result", "查询目录", Duration.ofSeconds(30)).run();
        String execution = toolExecutions.begin(OWNER_A, run, 1,
                new ToolCall("call-1", "get_course_outline", "{}"), "a".repeat(64));
        String resultRef = UUID.randomUUID().toString();
        String full = "{\"chapters\":[{\"id\":1,\"title\":\"完整目录\"}]}";
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE", () -> toolResults.completeWithResult(
                OWNER_A, COURSE_ID, run, execution, "get_course_outline", resultRef,
                "{\"duplicate\":1,\"duplicate\":2}", "{}", "[]", 12));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_tool_result", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_tool_execution WHERE id=?",
                String.class, execution)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT tool_result_bytes FROM assistant_run WHERE id=?",
                Long.class, run.id())).isZero();
        toolResults.completeWithResult(OWNER_A, COURSE_ID, run, execution, "get_course_outline", resultRef,
                full, "{\"count\":1,\"omitted\":true}", "[]", 12);

        var stored = toolResults.read(OWNER_A, COURSE_ID, run, resultRef);
        assertThat(stored.resultRef()).isEqualTo(resultRef);
        assertThat(stored.toolName()).isEqualTo("get_course_outline");
        assertThat(stored.resultJson()).isEqualTo(full);
        assertThat(stored.dependenciesJson()).isEqualTo("[]");
        assertThat(stored.resultHash()).matches("[a-f0-9]{64}");
        assertThat(stored.resultBytes()).isEqualTo(full.getBytes(StandardCharsets.UTF_8).length
                + "{\"count\":1,\"omitted\":true}".getBytes(StandardCharsets.UTF_8).length + 2);
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_tool_execution WHERE id=?",
                String.class, execution)).isEqualTo("COMPLETED");
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> toolResults.read(OWNER_B, COURSE_ID, run, resultRef));
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> toolResults.read(OWNER_A, COURSE_ID + 1, run, resultRef));
        var otherConversation = service.createConversation(OWNER_A, COURSE_ID, "其他运行");
        var otherRun = service.start(OWNER_A, otherConversation.id(), "other-tool-run", "查询", Duration.ofSeconds(30)).run();
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> toolResults.read(OWNER_A, COURSE_ID, otherRun, resultRef));

        jdbc.update("UPDATE assistant_tool_result SET result_json='{}' WHERE result_ref=?", resultRef);
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> toolResults.read(OWNER_A, COURSE_ID, run, resultRef));

        String readExecution = toolExecutions.begin(OWNER_A, run, 2,
                new ToolCall("call-2", "read_tool_result", "{}"), "b".repeat(64));
        assertThat(jdbc.queryForObject("SELECT tool_name FROM assistant_tool_execution WHERE id=?",
                String.class, readExecution)).isEqualTo("read_tool_result");
    }

    @Test void concurrentToolResultsSerializeRunByteLimitWithoutPartialExecutionCompletion() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "工具限额");
        var run = service.start(OWNER_A, conversation.id(), "tool-limit", "查询", Duration.ofSeconds(30)).run();
        var constrained = new AssistantLimits();
        constrained.setContextRedesignEnabled(true);
        constrained.setToolResultMaxBytes(4096);
        constrained.setToolRunResultMaxBytes(4096);
        constrained.setToolProjectionMaxBytes(2048);
        var store = new ChatToolResultStore(jdbc, new DataSourceTransactionManager(dataSource), constrained);
        String firstExecution = toolExecutions.begin(OWNER_A, run, 1,
                new ToolCall("limit-1", "search_courses", "{}"), "c".repeat(64));
        String secondExecution = toolExecutions.begin(OWNER_A, run, 2,
                new ToolCall("limit-2", "search_courses", "{}"), "d".repeat(64));
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        String payload = "{\"value\":\"" + "x".repeat(3000) + "\"}";
        Callable<String> first = () -> completeToolResult(barrier, store, run, firstExecution, payload);
        Callable<String> second = () -> completeToolResult(barrier, store, run, secondExecution, payload);
        try {
            var firstFuture = executor.submit(first);
            var secondFuture = executor.submit(second);
            assertThat(List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "ASSISTANT_TOOL_RESULT_LIMIT");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_tool_result", Integer.class)).isOne();
        assertThat(jdbc.queryForList("SELECT status FROM assistant_tool_execution WHERE id IN (?,?) ORDER BY status",
                String.class, firstExecution, secondExecution)).containsExactly("COMPLETED", "RUNNING");
    }

    @Test void toolExecutionCompletionFailureRollsBackInsertedResult() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "工具事务回滚");
        var run = service.start(OWNER_A, conversation.id(), "tool-rollback", "查询", Duration.ofSeconds(30)).run();
        String execution = toolExecutions.begin(OWNER_A, run, 1,
                new ToolCall("rollback-1", "search_courses", "{}"), "1".repeat(64));
        jdbc.execute("""
                CREATE TRIGGER reject_tool_execution_completion
                BEFORE UPDATE ON assistant_tool_execution FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic rejection'
                """);
        try {
            assertThatThrownBy(() -> toolResults.completeWithResult(OWNER_A, COURSE_ID, run, execution,
                    "search_courses", UUID.randomUUID().toString(), "{\"items\":[]}", "{}", "[]", 1))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_tool_execution_completion");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_tool_result", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_tool_execution WHERE id=?",
                String.class, execution)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT tool_result_bytes FROM assistant_run WHERE id=?",
                Long.class, run.id())).isZero();
    }

    @Test void expiredAndDeletedToolResultsCannotBeReadAndArePurgedInBoundedBatches() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "工具清理");
        var run = service.start(OWNER_A, conversation.id(), "tool-purge", "查询", Duration.ofSeconds(30)).run();
        var constrainedLimits = new AssistantLimits();
        constrainedLimits.setContextRedesignEnabled(true);
        constrainedLimits.setToolResultMaxBytes(4096);
        constrainedLimits.setToolRunResultMaxBytes(4096);
        constrainedLimits.setToolProjectionMaxBytes(2048);
        var constrained = new ChatToolResultStore(jdbc, new DataSourceTransactionManager(dataSource), constrainedLimits);
        String expiredExecution = toolExecutions.begin(OWNER_A, run, 1,
                new ToolCall("purge-1", "search_courses", "{}"), "e".repeat(64));
        String expiredRef = UUID.randomUUID().toString();
        String large = "{\"value\":\"" + "x".repeat(3000) + "\"}";
        constrained.completeWithResult(OWNER_A, COURSE_ID, run, expiredExecution, "search_courses",
                expiredRef, large, "{\"count\":0}", "[]", 1);
        jdbc.update("UPDATE assistant_tool_result SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE result_ref=?",
                expiredRef);
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> constrained.read(OWNER_A, COURSE_ID, run, expiredRef));
        constrainedLimits.setContextRedesignEnabled(false);
        constrained.purgeScheduled();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_tool_result WHERE result_ref=?", Integer.class, expiredRef)).isZero();

        String deletedExecution = toolExecutions.begin(OWNER_A, run, 2,
                new ToolCall("purge-2", "search_courses", "{}"), "f".repeat(64));
        assertBusinessCode("ASSISTANT_TOOL_RESULT_LIMIT", () -> constrained.completeWithResult(
                OWNER_A, COURSE_ID, run, deletedExecution, "search_courses", UUID.randomUUID().toString(),
                large, "{\"count\":0}", "[]", 1));
        assertThat(jdbc.queryForObject("SELECT tool_result_bytes FROM assistant_run WHERE id=?",
                Long.class, run.id())).isGreaterThan(3000L);

        String deletableExecution = toolExecutions.begin(OWNER_A, run, 3,
                new ToolCall("purge-3", "search_courses", "{}"), "0".repeat(64));
        String deletedRef = UUID.randomUUID().toString();
        toolResults.completeWithResult(OWNER_A, COURSE_ID, run, deletableExecution, "search_courses",
                deletedRef, "{\"items\":[]}", "{\"count\":0}", "[]", 1);
        var featureOff = new ChatPersistenceService(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        featureOff.delete(OWNER_A, conversation.id());
        assertThat(jdbc.queryForObject("SELECT deleted FROM assistant_tool_result WHERE result_ref=?",
                Integer.class, deletedRef)).isOne();
        assertBusinessCode("ASSISTANT_TOOL_RESULT_UNAVAILABLE",
                () -> toolResults.read(OWNER_A, COURSE_ID, run, deletedRef));
        assertThat(toolResults.purgeExpired()).isOne();
        long retainedBytes = jdbc.queryForObject("SELECT tool_result_bytes FROM assistant_run WHERE id=?",
                Long.class, run.id());
        executeIsolatedSql(findProjectFile(Path.of("sql", "migrations", "20261022_assistant_tool_result.sql")),
                false, "repeat tool result migration with existing schema");
        assertThat(jdbc.queryForObject("SELECT tool_result_bytes FROM assistant_run WHERE id=?",
                Long.class, run.id())).isEqualTo(retainedBytes);
    }

    @Test void disabledResultCleanupSkipsUnmigratedTable() throws Exception {
        jdbc.execute("DROP TABLE assistant_tool_result");
        try {
            var off = new ChatToolResultStore(jdbc, new DataSourceTransactionManager(dataSource), new AssistantLimits());
            off.purgeScheduled();
        } finally {
            executeIsolatedSql(findProjectFile(Path.of("sql", "migrations", "20261022_assistant_tool_result.sql")), false, "restore isolated result table");
        }
    }

    @Test void checkpointPublishesInAnswerTransactionAndRestoresAcrossServiceInstances() throws Exception {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "检查点");
        completedRound(conversation.id(), "history");
        var pending = pending(conversation.id(), null, 2);
        var publishing = service.start(OWNER_A, conversation.id(), "publish", "继续", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, publishing.id(), publishing.leaseOwner(), "回答", "[]", "{}", pending)).isTrue();
        var snapshot = service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID);
        assertThat(snapshot.revision()).isOne();
        assertThat(snapshot.coveredThroughSeq()).isEqualTo(2);
        assertThat(snapshot.sourceRunId()).isEqualTo(publishing.id());
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(usage_json, '$.checkpointOutcome')) FROM assistant_run WHERE id=?", String.class, publishing.id())).isEqualTo("PUBLISHED");
        assertThat(snapshot.coveredInputHash()).isEqualTo(ContextCheckpoint.inputHash(
                conversation.id(), 0, ContextCheckpoint.GENESIS, pending.events()));
        var limits = new AssistantLimits(); limits.setContextRedesignEnabled(true);
        var restarted = new ChatPersistenceService(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource), limits);
        assertThat(restarted.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID)).isEqualTo(snapshot);
        assertBusinessCode("CHAT_NOT_FOUND", () -> restarted.contextCheckpoint(OWNER_B, conversation.id(), COURSE_ID));
        assertBusinessCode("CHAT_NOT_FOUND", () -> restarted.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID + 1));
        executeIsolatedSql(findProjectFile(Path.of("sql", "migrations", "20261022_assistant_context_checkpoint.sql")), false, "repeat with data");
        assertThat(service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID)).isEqualTo(snapshot);
        assertThat(showCreateTable("assistant_context_checkpoint")).contains("UNIQUE KEY", "conversation_id", "COMMENT");
        var increment = pending(conversation.id(), snapshot, 4);
        var secondPublisher = service.start(OWNER_A, conversation.id(), "second-publisher", "滚动", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, secondPublisher.id(), secondPublisher.leaseOwner(), "新回答", "[]", "{}", increment)).isTrue();
        var rolled = service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID);
        assertThat(rolled.revision()).isEqualTo(2);
        assertThat(rolled.coveredFromSeq()).isOne();
        assertThat(rolled.coveredInputHash()).isEqualTo(ContextCheckpoint.inputHash(
                conversation.id(), 0, snapshot.coveredInputHash(), increment.events()));
        var legacy = new ChatPersistenceService(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        legacy.delete(OWNER_A, conversation.id()); // 回退后删除仍清理已经安装的检查点存储。
        assertThat(jdbc.queryForObject("SELECT deleted FROM assistant_context_checkpoint WHERE conversation_id = ?", Integer.class, conversation.id())).isOne();
    }

    @Test void checkpointRollbackAndTamperedRawProofRollbackAnswerTogether() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "回滚");
        completedRound(conversation.id(), "history");
        var pending = pending(conversation.id(), null, 2);
        var publishing = service.start(OWNER_A, conversation.id(), "publish", "继续", Duration.ofSeconds(30)).run();
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            assertThat(service.complete(OWNER_A, publishing.id(), publishing.leaseOwner(), "回答", "[]", "{}", pending)).isTrue();
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.run(OWNER_A, publishing.id()).status()).isEqualTo("RUNNING");
        assertThat(service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID)).isNull();
        var events = new java.util.ArrayList<>(pending.events());
        var original = events.get(0);
        var raw = original.message();
        events.set(0, new ContextCheckpoint.Event(new ChatPersistenceService.Message(raw.id(), raw.seq(), raw.role(),
                "tampered", raw.status(), raw.runId(), raw.sourcesJson()), original.runStatus(), original.userMessageId(), original.assistantMessageId()));
        var tampered = new ContextCheckpoint.Pending(0, ContextCheckpoint.GENESIS, 0, 0, 1, 2,
                "{}", "[]", pending.provenanceJson(), 1, "p", "m", "NONE", events);
        assertThatThrownBy(() -> service.complete(OWNER_A, publishing.id(), publishing.leaseOwner(), "回答", "[]", "{}", tampered))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.run(OWNER_A, publishing.id()).status()).isEqualTo("RUNNING");
        assertThat(service.messages(OWNER_A, conversation.id(), Long.MAX_VALUE, 100)).hasSize(3);
    }

    @Test void staleCheckpointCasSkipsPublicationButCommitsAnswerAndNeverOverwritesNewerCursor() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "CAS");
        completedRound(conversation.id(), "history");
        var first = service.start(OWNER_A, conversation.id(), "first", "继续", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, first.id(), first.leaseOwner(), "回答", "[]", "{}", pending(conversation.id(), null, 2))).isTrue();
        var old = service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID);
        var stale = pending(conversation.id(), old, 4);
        String newerHash = ContextCheckpoint.inputHash(conversation.id(), 0, old.coveredInputHash(), stale.events());
        jdbc.update("UPDATE assistant_context_checkpoint SET revision=2, covered_through_seq=4, covered_input_hash=?, provenance_json=? WHERE conversation_id=?", newerHash, stale.provenanceJson(), conversation.id());
        var next = service.start(OWNER_A, conversation.id(), "next", "下一步", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, next.id(), next.leaseOwner(), "新回答", "[]", "{}", stale)).isTrue();
        var retained = service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID);
        assertThat(retained.revision()).isEqualTo(2);
        assertThat(retained.coveredThroughSeq()).isEqualTo(4);
        assertThat(retained.coveredInputHash()).isEqualTo(newerHash);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(usage_json, '$.checkpointOutcome')) FROM assistant_run WHERE id=?", String.class, next.id())).isEqualTo("CAS_SKIPPED");
    }

    @Test void terminalOrphansRemainInHashWithoutPreventingLaterCompleteGroupCoverage() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "孤立用户消息");
        var cancelled = service.start(OWNER_A, conversation.id(), "cancel", "取消问题", Duration.ofSeconds(30)).run();
        service.cancel(OWNER_A, cancelled.id());
        completedRound(conversation.id(), "success");
        var pending = pending(conversation.id(), null, 3);
        assertThat(pending.events()).hasSize(3);
        assertThat(pending.coveredFromSeq()).isEqualTo(2);
        var next = service.start(OWNER_A, conversation.id(), "next", "下一步", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, next.id(), next.leaseOwner(), "回答", "[]", "{}", pending)).isTrue();
        var snapshot = service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID);
        assertThat(snapshot.coveredThroughSeq()).isEqualTo(3);
        assertThat(snapshot.coveredFromSeq()).isEqualTo(2);
        assertThat(service.contextEvents(OWNER_A, conversation.id(), 1, 1)).extracting(event -> event.message().role()).containsExactly("USER");
        assertThat(service.contextEvents(OWNER_A, conversation.id(), 2, 1)).extracting(event -> event.message().role()).containsExactly("ASSISTANT");
    }

    @Test void cancelledOrExpiredPublishingRunCannotAdvanceCheckpoint() {
        var conversation = service.createConversation(OWNER_A, COURSE_ID, "取消发布");
        completedRound(conversation.id(), "history");
        var pending = pending(conversation.id(), null, 2);
        var cancelled = service.start(OWNER_A, conversation.id(), "cancel", "取消", Duration.ofSeconds(30)).run();
        service.cancel(OWNER_A, cancelled.id());
        assertThat(service.complete(OWNER_A, cancelled.id(), cancelled.leaseOwner(), "迟到", "[]", "{}", pending)).isFalse();
        var expired = service.start(OWNER_A, conversation.id(), "expire", "超时", Duration.ofSeconds(30)).run();
        jdbc.update("UPDATE assistant_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", expired.id());
        assertThat(service.complete(OWNER_A, expired.id(), expired.leaseOwner(), "迟到", "[]", "{}", pending)).isFalse();
        assertThat(service.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID)).isNull();
    }

    @Test void disabledContextCanDeleteConversationBeforeCheckpointMigrationIsInstalled() throws Exception {
        jdbc.execute("DROP TABLE assistant_context_checkpoint"); // 仅本测试随机隔离库，finally恢复结构。
        try {
            var legacy = new ChatPersistenceService(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
            var conversation = legacy.createConversation(OWNER_A, COURSE_ID, "旧库");
            assertThat(legacy.contextCheckpoint(OWNER_A, conversation.id(), COURSE_ID)).isNull();
            assertThat(legacy.delete(OWNER_A, conversation.id())).isEmpty();
        } finally {
            executeIsolatedSql(findProjectFile(Path.of("sql", "migrations", "20261022_assistant_context_checkpoint.sql")), false, "restore isolated checkpoint table");
        }
    }

    private void completedRound(String conversation, String request) {
        var run = service.start(OWNER_A, conversation, request, "问题", Duration.ofSeconds(30)).run();
        assertThat(service.complete(OWNER_A, run.id(), run.leaseOwner(), "答案", "[]", "{}")).isTrue();
    }

    private ContextCheckpoint.Pending pending(String conversation, ContextCheckpoint.Snapshot previous, long through) {
        long base = previous == null ? 0 : previous.coveredThroughSeq();
        var events = service.contextEvents(OWNER_A, conversation, base, 1000).stream().filter(event -> event.message().seq() <= through).toList();
        long from = previous == null ? events.stream().filter(event -> "USER".equals(event.message().role())
                && "COMPLETED".equals(event.runStatus())).mapToLong(event -> event.message().seq()).min().orElseThrow() : previous.coveredFromSeq();
        String provenance;
        try { provenance = new ObjectMapper().writeValueAsString(java.util.Map.of("version", 1, "sources", List.of(),
                "barrier", com.fasterxml.jackson.databind.node.NullNode.instance,
                "boundary", ContextCheckpoint.boundaryProof(events.get(events.size() - 1)))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return new ContextCheckpoint.Pending(previous == null ? 0 : previous.revision(), previous == null ? ContextCheckpoint.GENESIS : previous.coveredInputHash(),
                base, 0, from, through, "{}", "[]", provenance, 1, "p", "m", "NONE", events);
    }

    private void initializeSchema() throws Exception {
        executeIsolatedSql(schemaPath(), true, "isolated copy of sql/schema.sql");
        executeIsolatedSql(migrationPath(), false, "isolated copy of assistant chat migration");
        executeIsolatedSql(migrationPath(), false, "second isolated copy of assistant chat migration");
        Path contextMigration = findProjectFile(Path.of("sql", "migrations", "20261022_assistant_context_checkpoint.sql"));
        executeIsolatedSql(contextMigration, false, "isolated context checkpoint migration");
        executeIsolatedSql(contextMigration, false, "repeated isolated context checkpoint migration");
        Path resultMigration = findProjectFile(Path.of("sql", "migrations", "20261022_assistant_tool_result.sql"));
        executeIsolatedSql(resultMigration, false, "isolated tool result migration");
        executeIsolatedSql(resultMigration, false, "repeated isolated tool result migration");
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

    private String completeToolResult(CyclicBarrier barrier, ChatToolResultStore store,
                                      ChatPersistenceService.Run run, String execution, String payload) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            store.completeWithResult(OWNER_A, COURSE_ID, run, execution, "search_courses",
                    UUID.randomUUID().toString(), payload, "{}", "[]", 1);
            return "OK";
        } catch (BusinessException error) {
            return error.getCode();
        }
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
