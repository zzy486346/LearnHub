package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatPersistenceService.Run;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 有界保存只读工具完整结果，并在每次回读时重新约束当前运行身份。 */
@Service
public class ChatToolResultStore {
    private static final Logger log = LoggerFactory.getLogger(ChatToolResultStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("search_courses", "get_course_outline",
            "search_course_materials", "get_my_learning_progress", "read_tool_result");

    private final DeadlineJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AssistantLimits limits;

    public ChatToolResultStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               AssistantLimits limits) {
        this.jdbc = new DeadlineJdbcTemplate(Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource"));
        this.jdbc.setQueryTimeout(10);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public void completeWithResult(long owner, long course, Run stored, String executionId,
                                   String toolName, String resultRef, String resultJson,
                                   String projectionJson, String dependenciesJson, long durationMillis) {
        validateIdentity(owner, course, stored, executionId, toolName, resultRef, durationMillis);
        String full = strictObject(resultJson, "工具完整结果");
        String projection = strictObject(projectionJson, "工具结果投影");
        String dependencies = dependencies(dependenciesJson);
        long projectionBytes = utf8(projection);
        long resultBytes = utf8(full) + projectionBytes + utf8(dependencies);
        if (projectionBytes > limits.getToolProjectionMaxBytes()) throw limit();
        if (resultBytes > limits.getToolResultMaxBytes()) throw limit();
        String resultHash = sha256(full);
        transactions.executeWithoutResult(status -> {
            var conversations = jdbc.queryForList("""
                    SELECT id FROM assistant_conversation
                    WHERE id = ? AND owner_user_id = ? AND course_id = ? AND deleted = 0
                    FOR UPDATE
                    """, String.class, stored.conversationId(), owner, course);
            if (conversations.size() != 1) throw unavailable();
            var runBytes = jdbc.queryForList("""
                    SELECT tool_result_bytes FROM assistant_run
                    WHERE id = ? AND owner_user_id = ? AND conversation_id = ?
                      AND status = 'RUNNING' AND lease_owner = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP(3)
                    FOR UPDATE
                    """, Long.class, stored.id(), owner, stored.conversationId(), stored.leaseOwner());
            if (runBytes.size() != 1) throw unavailable();
            var executions = jdbc.queryForList("""
                    SELECT id FROM assistant_tool_execution
                    WHERE id = ? AND run_id = ? AND tool_name = ? AND status = 'RUNNING'
                    FOR UPDATE
                    """, String.class, executionId, stored.id(), toolName);
            if (executions.size() != 1) throw unavailable();
            if (runBytes.get(0) + resultBytes > limits.getToolRunResultMaxBytes()) throw limit();
            int reserved = jdbc.update("""
                    UPDATE assistant_run
                    SET tool_result_bytes = tool_result_bytes + ?
                    WHERE id = ? AND owner_user_id = ? AND conversation_id = ?
                      AND status = 'RUNNING' AND lease_owner = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP(3)
                      AND tool_result_bytes + ? <= ?
                    """, resultBytes, stored.id(), owner, stored.conversationId(), stored.leaseOwner(),
                    resultBytes, limits.getToolRunResultMaxBytes());
            if (reserved != 1) throw unavailable();
            jdbc.update("""
                    INSERT INTO assistant_tool_result
                        (result_ref, execution_id, owner_user_id, course_id, conversation_id, run_id,
                         tool_name, result_json, projection_json, dependencies_json, result_hash,
                         result_bytes, expires_at, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND), 0)
                    """, resultRef, executionId, owner, course, stored.conversationId(), stored.id(), toolName,
                    full, projection, dependencies, resultHash, resultBytes, limits.getToolResultTtlSeconds());
            int completed = jdbc.update("""
                    UPDATE assistant_tool_execution te
                    JOIN assistant_run r ON r.id = te.run_id
                    JOIN assistant_conversation c ON c.id = r.conversation_id
                    SET te.status = 'COMPLETED', te.result_summary = '只读工具结果已安全保存',
                        te.duration_ms = ?, te.error_code = NULL
                    WHERE te.id = ? AND te.run_id = ? AND te.tool_name = ? AND te.status = 'RUNNING'
                      AND r.owner_user_id = ? AND r.conversation_id = ? AND r.status = 'RUNNING'
                      AND r.lease_owner = ? AND r.lease_expires_at > CURRENT_TIMESTAMP(3)
                      AND c.id = r.conversation_id AND c.owner_user_id = r.owner_user_id
                      AND c.course_id = ? AND c.deleted = 0
                    """, durationMillis, executionId, stored.id(), toolName, owner,
                    stored.conversationId(), stored.leaseOwner(), course);
            if (completed != 1) throw unavailable();
        });
    }

    public Stored read(long owner, long course, Run stored, String resultRef) {
        validateRun(owner, course, stored);
        if (!safeId(resultRef)) throw unavailable();
        var rows = jdbc.query("""
                SELECT tr.result_ref, tr.tool_name, tr.result_json, tr.dependencies_json,
                       tr.result_hash, tr.result_bytes
                FROM assistant_tool_result tr
                JOIN assistant_tool_execution te ON te.id = tr.execution_id AND te.run_id = tr.run_id
                JOIN assistant_run r ON r.id = tr.run_id AND r.conversation_id = tr.conversation_id
                JOIN assistant_conversation c ON c.id = tr.conversation_id
                WHERE tr.result_ref = ? AND tr.owner_user_id = ? AND tr.course_id = ?
                  AND tr.run_id = ? AND tr.conversation_id = ? AND tr.deleted = 0
                  AND tr.expires_at > CURRENT_TIMESTAMP(3) AND te.status = 'COMPLETED'
                  AND te.tool_name = tr.tool_name
                  AND r.owner_user_id = tr.owner_user_id AND r.status = 'RUNNING'
                  AND r.lease_owner = ? AND r.lease_expires_at > CURRENT_TIMESTAMP(3)
                  AND c.owner_user_id = tr.owner_user_id AND c.course_id = tr.course_id AND c.deleted = 0
                """, (rs, row) -> new Stored(rs.getString("result_ref"), rs.getString("tool_name"),
                rs.getString("result_json"), rs.getString("dependencies_json"),
                rs.getString("result_hash"), rs.getLong("result_bytes")),
                resultRef, owner, course, stored.id(), stored.conversationId(), stored.leaseOwner());
        if (rows.size() != 1) throw unavailable();
        Stored storedResult = rows.get(0);
        if (!sha256(storedResult.resultJson()).equals(storedResult.resultHash())) throw unavailable();
        return storedResult;
    }

    public int purgeExpired() {
        return jdbc.update("""
                DELETE FROM assistant_tool_result
                WHERE expires_at <= CURRENT_TIMESTAMP(3) OR deleted = 1
                ORDER BY expires_at, result_ref
                LIMIT 100
                """);
    }

    @Scheduled(fixedDelayString = "${learnhub.assistant.tool-result-cleanup-delay:60000}")
    public void purgeScheduled() {
        try {
            if (!limits.isContextRedesignEnabled()) {
                Integer installed = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='assistant_tool_result'", Integer.class);
                if (installed == null || installed == 0) return;
            }
            purgeExpired();
        }
        catch (RuntimeException failure) {
            log.warn("工具结果过期清理失败 errorType={}", failure.getClass().getSimpleName());
        }
    }

    private static void validateIdentity(long owner, long course, Run stored, String executionId,
                                         String toolName, String resultRef, long durationMillis) {
        validateRun(owner, course, stored);
        if (!safeId(executionId) || !safeId(resultRef) || !TOOLS.contains(toolName)
                || durationMillis < 0 || durationMillis > 300_000) throw unavailable();
    }

    private static void validateRun(long owner, long course, Run stored) {
        if (owner <= 0 || course <= 0 || stored == null || stored.ownerUserId() != owner
                || !safeId(stored.id()) || !safeId(stored.conversationId()) || !safeId(stored.leaseOwner()))
            throw unavailable();
    }

    private static String strictObject(String value, String label) {
        if (value == null || value.isBlank()) throw unavailable();
        try {
            JsonNode parsed = JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (!parsed.isObject()) throw unavailable();
            return value;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("ASSISTANT_TOOL_RESULT_UNAVAILABLE", label + "格式无效");
        }
    }

    private static String dependencies(String value) {
        if (value == null || value.length() > 100_000) throw unavailable();
        try { ChatEvidenceCodec.parse(value); return value; }
        catch (RuntimeException invalid) { throw unavailable(); }
    }

    private static long utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static boolean safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static BusinessException limit() {
        return new BusinessException("ASSISTANT_TOOL_RESULT_LIMIT", "工具结果超出受控存储上限");
    }

    private static BusinessException unavailable() {
        return new BusinessException("ASSISTANT_TOOL_RESULT_UNAVAILABLE", "工具结果不可用或无权读取");
    }

    public record Stored(String resultRef, String toolName, String resultJson, String dependenciesJson,
                         String resultHash, long resultBytes) { }
}
