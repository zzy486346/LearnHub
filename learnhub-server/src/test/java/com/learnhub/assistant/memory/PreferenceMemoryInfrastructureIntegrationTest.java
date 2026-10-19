package com.learnhub.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="learnhub.memory.it.enabled", matches="true")
class PreferenceMemoryInfrastructureIntegrationTest {
    private String database;
    private String server;
    private String username;
    private String password;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private CourseService courses;
    private Mem0Client client;
    private PreferenceMemoryService service;

    @BeforeAll void initialize() throws Exception {
        database = "learnhub_memory_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        server = "jdbc:mysql://" + env("LEARNHUB_CHAT_IT_MYSQL_HOST", "localhost") + ":"
                + env("LEARNHUB_CHAT_IT_MYSQL_PORT", "3306")
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        username = env("LEARNHUB_CHAT_IT_MYSQL_ADMIN_USERNAME", "learnhub");
        password = env("LEARNHUB_CHAT_IT_MYSQL_ADMIN_PASSWORD", "learnhub");
        try {
            try (Connection conn = DriverManager.getConnection(server, username, password); var sql = conn.createStatement()) {
                sql.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            dataSource = new DriverManagerDataSource(server.replace("/?", "/" + database + "?"), username, password);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE assistant_conversation(id CHAR(36) PRIMARY KEY,owner_user_id BIGINT,course_id BIGINT,deleted TINYINT DEFAULT 0,status VARCHAR(20) DEFAULT 'ACTIVE')");
            jdbc.execute("CREATE TABLE assistant_run(id VARCHAR(64) PRIMARY KEY,owner_user_id BIGINT,conversation_id VARCHAR(64),status VARCHAR(20),lease_owner VARCHAR(64),lease_expires_at DATETIME(3))");
            Path migration = Path.of("sql/migrations/V20261019__assistant_memory.sql");
            if (!Files.exists(migration)) migration = Path.of("..").resolve(migration);
            byte[] sql = Files.readAllBytes(migration);
            for (int i = 0; i < 2; i++) try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new EncodedResource(new ByteArrayResource(sql), StandardCharsets.UTF_8));
            }
            Path extraction = migration.resolveSibling("V20261019__assistant_preference_extraction.sql");
            for (int i = 0; i < 2; i++) try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new EncodedResource(new ByteArrayResource(Files.readAllBytes(extraction)), StandardCharsets.UTF_8));
            }
        } catch (Exception failure) { cleanup(); throw failure; }
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        jdbc.update("DELETE FROM assistant_learning_preference");
        jdbc.update("DELETE FROM assistant_preference_extraction_attempt");
        jdbc.update("DELETE FROM assistant_run");
        jdbc.update("DELETE FROM assistant_conversation");
        courses = mock(CourseService.class);
        client = mock(Mem0Client.class);
        ObjectProvider<Mem0Client> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        service = new PreferenceMemoryService(dataSource, courses, provider, true);
    }
    @AfterAll void cleanup() throws Exception {
        if (database == null || !database.matches("learnhub_memory_it_[a-f0-9]{24}")) return;
        try (Connection conn = DriverManager.getConnection(server, username, password); var sql = conn.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }
    @Test void schemaCommentsAndMigrationAreComplete() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='assistant_learning_preference' AND TRIM(COLUMN_COMMENT)=''", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='assistant_learning_preference'", String.class)).contains("学习");
    }
    @Test void activeCreateOwnerIsolationAndCurrentVersionOnly() {
        var value = service.create(101, "STYLE", "喜欢Java示例");
        assertThat(value.status()).isEqualTo("ACTIVE");
        when(client.search(101, "怎么学", 12)).thenReturn(List.of(new Mem0Client.Hit(value.id(), 1),new Mem0Client.Hit(value.id(), 1)));
        assertThat(service.context(101, "怎么学", 3)).hasSize(1);
        assertThat(service.list(102)).isEmpty();
        code("MEMORY_NOT_FOUND", () -> service.current(102, value.id()));
        var updated = service.update(101, value.id(), 1, "PACE", "每天分两次学习");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.context(101, "怎么学", 3)).isEmpty();
        code("MEMORY_VERSION_CONFLICT", () -> service.update(101, value.id(), 1, "STYLE", "先举例"));
    }
    @Test void failuresRemainPendingAndRetryAfterRestartIsIdempotent() {
        doThrow(new IllegalStateException("sensitive upstream body")).when(client).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        var value = service.create(101, "LEVEL", "Java入门");
        assertThat(value.status()).isEqualTo("PENDING");
        code("MEMORY_CHANGED", () -> service.current(101, value.id()));
        assertThat(jdbc.queryForObject("SELECT attempts FROM assistant_learning_preference WHERE id=?", Integer.class, value.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM assistant_learning_preference WHERE id=?", String.class, value.id())).isEqualTo("MEMORY_UNAVAILABLE");
        reset(client);
        jdbc.update("UPDATE assistant_learning_preference SET next_retry_at=CURRENT_TIMESTAMP(3) WHERE id=?", value.id());
        service.syncDue(); service.syncDue();
        verify(client, times(1)).upsert(101, value.id(), 1, "LEVEL", "Java入门");
        assertThat(service.current(101, value.id()).text()).isEqualTo("Java入门");
    }
    @Test void forgetImmediatelyTombstonesEvenWhenRemoteFailsThenCompensates() {
        var value = service.create(101, "GOAL", "掌握数据库事务");
        doThrow(new IllegalStateException()).when(client).delete(101, value.id(), 2);
        var forgotten = service.forget(101, value.id(), 1);
        assertThat(forgotten.status()).isEqualTo("DELETING");
        assertThat(forgotten.text()).isEmpty();
        assertThat(service.list(101)).isEmpty();
        code("MEMORY_NOT_FOUND", () -> service.current(101, value.id()));
        reset(client);
        jdbc.update("UPDATE assistant_learning_preference SET next_retry_at=CURRENT_TIMESTAMP(3) WHERE id=?", value.id());
        service.syncDue();
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_learning_preference WHERE id=?", String.class, value.id())).isEqualTo("DELETED");
        verify(client).delete(101, value.id(), 2);
    }
    @Test void lateSuccessfulResponseCannotActivateNewerIntent() {
        doAnswer(call -> {
            String id = call.getArgument(1);
            jdbc.update("UPDATE assistant_learning_preference SET version=2,status='PENDING',text='先看代码' WHERE id=?", id);
            return null;
        }).when(client).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        var value = service.create(101, "STYLE", "先看例子");
        assertThat(value.version()).isEqualTo(2);
        assertThat(value.status()).isEqualTo("PENDING");
    }
    @Test void sourcedPreferenceRechecksOwnerConversationDeletionAndCourse() {
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO assistant_conversation(id,owner_user_id,course_id) VALUES (?,101,501)", conversation);
        code("MEMORY_NOT_FOUND", () -> service.create(102, "STYLE", "先举例", conversation));
        var value = service.create(101, "STYLE", "先举例", conversation);
        assertThat(value.sourceCourseId()).isEqualTo(501);
        doThrow(new BusinessException("COURSE_NOT_FOUND", "不可用")).when(courses).requireAvailable(501L, 101L);
        code("COURSE_NOT_FOUND", () -> service.current(101, value.id()));
        reset(courses);
        jdbc.update("UPDATE assistant_conversation SET deleted=1 WHERE id=?", conversation);
        code("MEMORY_NOT_FOUND", () -> service.current(101, value.id()));
    }
    @Test @SuppressWarnings("unchecked") void disabledFeatureNeverConsultsInfrastructure() {
        ObjectProvider<Mem0Client> provider = mock(ObjectProvider.class);
        var disabled = new PreferenceMemoryService(dataSource, courses, provider, false);
        assertThat(disabled.context(101, "问题", 3)).isEmpty();
        code("MEMORY_DISABLED", () -> disabled.create(101, "GOAL", "掌握Java"));
        code("MEMORY_CHANGED", () -> disabled.current(101, UUID.randomUUID().toString()));
        disabled.syncDue();
        verifyNoInteractions(provider, courses);
    }
    @Test void remoteFailureDegradesContextAndCannotForgeLocalText() {
        var a = service.create(101, "GOAL", "掌握Java");
        var b = service.create(102, "GOAL", "掌握Python");
        when(client.search(101, "学习", 12)).thenReturn(List.of(new Mem0Client.Hit(b.id(), 1),new Mem0Client.Hit(a.id(), 99),new Mem0Client.Hit("invalid", 1)));
        assertThat(service.context(101, "学习", 3)).isEmpty();
        when(client.search(101, "学习", 12)).thenThrow(new IllegalStateException());
        assertThat(service.context(101, "学习", 3)).isEmpty();
    }
    @Test void compensationIsBoundedToTwentyCurrentIntents() {
        doThrow(new IllegalStateException()).when(client).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        for (int i = 0; i < 23; i++) service.create(101, "GOAL", "学习第" + i + "章");
        reset(client);
        jdbc.update("UPDATE assistant_learning_preference SET next_retry_at=CURRENT_TIMESTAMP(3)");
        service.syncDue();
        verify(client, times(20)).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_learning_preference WHERE status='PENDING'", Integer.class)).isEqualTo(3);
    }
    @Test void pendingRetryDoesNotResendAfterSourceConversationDeleted() {
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO assistant_conversation(id,owner_user_id,course_id) VALUES (?,101,501)", conversation);
        doThrow(new IllegalStateException()).when(client).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        var value = service.create(101, "STYLE", "先举例", conversation);
        assertThat(value.status()).isEqualTo("PENDING");
        reset(client);
        jdbc.update("UPDATE assistant_conversation SET deleted=1 WHERE id=?", conversation);
        jdbc.update("UPDATE assistant_learning_preference SET next_retry_at=CURRENT_TIMESTAMP(3) WHERE id=?", value.id());
        service.syncDue();
        verifyNoInteractions(client);
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_learning_preference WHERE id=?", String.class, value.id())).isEqualTo("PENDING");
        code("MEMORY_CHANGED", () -> service.current(101, value.id()));
    }
    @Test void pendingRetryDoesNotResendAfterSourceCourseRevoked() {
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO assistant_conversation(id,owner_user_id,course_id) VALUES (?,101,501)", conversation);
        doThrow(new IllegalStateException()).when(client).upsert(anyLong(), anyString(), anyLong(), anyString(), anyString());
        var value = service.create(101, "STYLE", "先举例", conversation);
        assertThat(value.status()).isEqualTo("PENDING");
        reset(client);
        doThrow(new BusinessException("COURSE_NOT_FOUND", "不可用")).when(courses).requireAvailable(501L, 101L);
        jdbc.update("UPDATE assistant_learning_preference SET next_retry_at=CURRENT_TIMESTAMP(3) WHERE id=?", value.id());
        service.syncDue();
        verifyNoInteractions(client);
        assertThat(jdbc.queryForObject("SELECT status FROM assistant_learning_preference WHERE id=?", String.class, value.id())).isEqualTo("PENDING");
    }
    @Test void ownerQuotaAllowsFiftyAndForgottenRecordsReleaseCapacity() {
        seedQuota(101, 49);
        var last = service.create(101, "STYLE", "先举例");
        code("MEMORY_LIMIT_EXCEEDED", () -> service.create(101, "STYLE", "先讲原理"));
        assertThat(service.create(102, "STYLE", "慢速讲解").status()).isEqualTo("ACTIVE");
        service.forget(101, last.id(), 1);
        service.create(101, "STYLE", "按步骤讲解");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_learning_preference WHERE owner_user_id=101 AND deleted=0", Integer.class)).isEqualTo(50);
    }
    @Test void concurrentFinalQuotaSlotCannotCreateFiftyFirstPreference() throws Exception {
        seedQuota(101, 49);
        var ready = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) attempts.add(executor.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                try { service.create(101, "STYLE", "先举例"); return "CREATED"; }
                catch (BusinessException limited) { return limited.getCode(); }
            }));
            var outcomes = new java.util.ArrayList<String>();
            for (var attempt : attempts) outcomes.add(attempt.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "MEMORY_LIMIT_EXCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_learning_preference WHERE owner_user_id=101 AND deleted=0", Integer.class)).isEqualTo(50);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void extractionAuditIsDurableAndNeverStoresOriginalText() {
        var run = extractionRun();
        var audit = new PreferenceExtractionAttemptStore(dataSource);
        assertThat(audit.claim(101, run, "deepseek-flash", "literal-v1", 1200)).isTrue();
        assertThat(jdbc.queryForObject("SELECT actual_total_tokens FROM assistant_preference_extraction_attempt WHERE run_id=?", Integer.class, run.id())).isNull();
        audit.reserved(101, run.id(), 1328);
        audit.usage(101, run.id(), 10, 5, 15);
        audit.finish(101, run.id(), "NO_MATCH", null, null);
        var restarted = new PreferenceExtractionAttemptStore(dataSource);
        assertThat(restarted.claim(101, run, "deepseek-flash", "literal-v1", 1200)).isFalse();
        assertThat(jdbc.queryForObject("SELECT actual_total_tokens FROM assistant_preference_extraction_attempt WHERE run_id=?", Integer.class, run.id())).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='assistant_preference_extraction_attempt' AND (TRIM(COLUMN_COMMENT)='' OR COLUMN_NAME IN ('text','message','response','api_key'))", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='assistant_preference_extraction_attempt'", String.class)).contains("不保存原文");
    }
    @Test void extractionClaimRequiresOwnerLeaseAndActiveConversation() {
        var run = extractionRun();
        var audit = new PreferenceExtractionAttemptStore(dataSource);
        assertThat(audit.claim(102, run, "deepseek-flash", "literal-v1", 1200)).isFalse();
        jdbc.update("UPDATE assistant_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
        assertThat(audit.claim(101, run, "deepseek-flash", "literal-v1", 1200)).isFalse();
        jdbc.update("UPDATE assistant_run SET lease_expires_at=TIMESTAMPADD(MINUTE,1,CURRENT_TIMESTAMP(3))");
        jdbc.update("UPDATE assistant_conversation SET deleted=1");
        assertThat(audit.claim(101, run, "deepseek-flash", "literal-v1", 1200)).isFalse();
        jdbc.update("UPDATE assistant_conversation SET deleted=0");
        assertThat(audit.claim(101, run, "deepseek-flash", "literal-v1", 1200)).isTrue();
    }
    @Test void concurrentExtractionClaimAndUnknownRestartDoNotRepeatModelAttempt() throws Exception {
        var run = extractionRun();
        var ready = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                ready.await(3, TimeUnit.SECONDS);
                return new PreferenceExtractionAttemptStore(dataSource).claim(101, run, "deepseek-flash", "literal-v1", 1200);
            }));
            assertThat(List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            var audit = new PreferenceExtractionAttemptStore(dataSource);
            audit.reserved(101, run.id(), 1328);
            audit.finish(101, run.id(), "UNKNOWN", "EXTRACTION_FAILED", null);
            assertThat(new PreferenceExtractionAttemptStore(dataSource).claim(101, run, "deepseek-flash", "literal-v1", 1200)).isFalse();
            assertThat(jdbc.queryForObject("SELECT reserved_tokens FROM assistant_preference_extraction_attempt WHERE run_id=?", Integer.class, run.id())).isEqualTo(1328);
            assertThat(jdbc.queryForObject("SELECT actual_total_tokens FROM assistant_preference_extraction_attempt WHERE run_id=?", Integer.class, run.id())).isNull();
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void extractionAuditCannotRecordUsageUnderWrongOwnerOrBeforeReservation() {
        var run = extractionRun();
        var audit = new PreferenceExtractionAttemptStore(dataSource);
        audit.claim(101, run, "deepseek-flash", "literal-v1", 1200);
        assertThatThrownBy(() -> audit.usage(101, run.id(), 1, 1, 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit.reserved(102, run.id(), 1328)).isInstanceOf(IllegalStateException.class);
        audit.reserved(101, run.id(), 1328);
        assertThatThrownBy(() -> audit.usage(102, run.id(), 1, 1, 2)).isInstanceOf(IllegalStateException.class);
    }
    private com.learnhub.assistant.chat.application.ChatPersistenceService.Run extractionRun() {
        String conversation = UUID.randomUUID().toString(); String run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO assistant_conversation(id,owner_user_id,course_id) VALUES (?,101,501)", conversation);
        jdbc.update("INSERT INTO assistant_run VALUES (?,101,?,'RUNNING','lease',TIMESTAMPADD(MINUTE,1,CURRENT_TIMESTAMP(3)))", run, conversation);
        return new com.learnhub.assistant.chat.application.ChatPersistenceService.Run(run, conversation, 101, "request", "RUNNING", "lease", "我喜欢先看例子");
    }
    private void seedQuota(long owner, int count) {
        for (int i = 0; i < count; i++) jdbc.update("""
                INSERT INTO assistant_learning_preference(id,owner_user_id,category,text,status)
                VALUES (?,?,'STYLE','先举例','ACTIVE')
                """, UUID.randomUUID().toString(), owner);
    }
    private static String env(String name, String fallback) { String value = System.getenv(name); return value == null ? fallback : value; }
    private static void code(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
