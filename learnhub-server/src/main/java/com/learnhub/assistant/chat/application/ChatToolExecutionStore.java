package com.learnhub.assistant.chat.application;

import com.learnhub.assistant.chat.application.ChatPersistenceService.Run;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import com.learnhub.common.exception.BusinessException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 只保存工具身份、参数摘要和安全状态，不保存参数或结果正文。 */
@Service
public class ChatToolExecutionStore {
    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutionStore.class);
    private static final Set<String> TOOLS = Set.of("search_courses", "get_course_outline",
            "search_course_materials", "get_my_learning_progress", "read_tool_result");
    private final DeadlineJdbcTemplate jdbc;
    @Value("${learnhub.assistant.chat-enabled:false}")
    private boolean chatEnabled;

    @Autowired
    public ChatToolExecutionStore(JdbcTemplate jdbc) {
        this(new DeadlineJdbcTemplate(Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource")));
    }

    ChatToolExecutionStore(DeadlineJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbc.setQueryTimeout(10);
    }

    public String begin(long owner, Run stored, int order, ToolCall call, String argumentsHash) {
        validateRun(owner, stored);
        if (order < 1 || order > 32 || call == null || !TOOLS.contains(call.name())
                || !call.id().matches("[A-Za-z0-9_-]{1,128}")
                || argumentsHash == null || !argumentsHash.matches("[a-f0-9]{64}")) throw invalid();
        String id = UUID.randomUUID().toString();
        int changed;
        try {
            changed = jdbc.update("""
                    INSERT INTO assistant_tool_execution
                        (id, run_id, execution_order, tool_name, tool_call_id, status, arguments_hash)
                    SELECT ?, r.id, ?, ?, ?, 'RUNNING', ?
                    FROM assistant_run r JOIN assistant_conversation c ON c.id = r.conversation_id
                    WHERE r.id = ? AND r.owner_user_id = ? AND r.conversation_id = ?
                      AND r.status = 'RUNNING' AND r.lease_owner = ?
                      AND r.lease_expires_at > CURRENT_TIMESTAMP(3)
                      AND c.owner_user_id = r.owner_user_id AND c.deleted = 0
                    """, id, order, call.name(), call.id(), argumentsHash,
                    stored.id(), owner, stored.conversationId(), stored.leaseOwner());
        } catch (DuplicateKeyException duplicate) {
            throw cancelled();
        }
        if (changed != 1) throw cancelled();
        return id;
    }

    public void complete(long owner, Run stored, String executionId, long durationMillis) {
        validateRun(owner, stored);
        validateExecution(executionId, durationMillis);
        int changed = jdbc.update("""
                UPDATE assistant_tool_execution t
                JOIN assistant_run r ON r.id = t.run_id
                JOIN assistant_conversation c ON c.id = r.conversation_id
                SET t.status = 'COMPLETED', t.result_summary = '只读工具已完成',
                    t.duration_ms = ?, t.error_code = NULL
                WHERE t.id = ? AND t.status = 'RUNNING'
                  AND r.id = ? AND r.owner_user_id = ? AND r.conversation_id = ?
                  AND r.status = 'RUNNING' AND r.lease_owner = ?
                  AND r.lease_expires_at > CURRENT_TIMESTAMP(3)
                  AND c.owner_user_id = r.owner_user_id AND c.deleted = 0
                """, durationMillis, executionId, stored.id(), owner, stored.conversationId(), stored.leaseOwner());
        if (changed != 1) throw cancelled();
    }

    public void fail(long owner, Run stored, String executionId, long durationMillis,
                     String safeErrorCode, boolean cancelled) {
        validateRun(owner, stored);
        validateExecution(executionId, durationMillis);
        if (safeErrorCode == null || !safeErrorCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        int changed = jdbc.update("""
                UPDATE assistant_tool_execution t
                JOIN assistant_run r ON r.id = t.run_id
                JOIN assistant_conversation c ON c.id = r.conversation_id
                SET t.status = CASE WHEN ? OR r.status <> 'RUNNING' OR c.deleted <> 0
                        OR r.lease_expires_at IS NULL OR r.lease_expires_at <= CURRENT_TIMESTAMP(3)
                        THEN 'CANCELLED' ELSE 'FAILED' END,
                    t.result_summary = NULL, t.duration_ms = ?, t.error_code = ?
                WHERE t.id = ? AND t.status = 'RUNNING'
                  AND r.id = ? AND r.owner_user_id = ? AND r.conversation_id = ?
                  AND c.owner_user_id = r.owner_user_id
                  AND (r.status <> 'RUNNING' OR r.lease_owner = ?)
                """, cancelled, durationMillis, safeErrorCode, executionId,
                stored.id(), owner, stored.conversationId(), stored.leaseOwner());
        if (changed != 1) throw cancelled();
    }

    /** 取消后的原 IO scope 可能禁止写库；独立调度线程补偿遗留工具状态。 */
    public int recoverTerminated() {
        var ids = jdbc.queryForList("""
                SELECT t.id FROM assistant_tool_execution t
                JOIN assistant_run r ON r.id = t.run_id
                JOIN assistant_conversation c ON c.id = r.conversation_id
                WHERE t.status = 'RUNNING' AND c.owner_user_id = r.owner_user_id
                  AND (r.status <> 'RUNNING' OR r.lease_expires_at IS NULL
                       OR r.lease_expires_at <= CURRENT_TIMESTAMP(3) OR c.deleted <> 0)
                ORDER BY t.created_at, t.id LIMIT 100
                """, String.class);
        int recovered = 0;
        for (String id : ids) {
            recovered += jdbc.update("""
                    UPDATE assistant_tool_execution t
                    JOIN assistant_run r ON r.id = t.run_id
                    JOIN assistant_conversation c ON c.id = r.conversation_id
                    SET t.status = 'CANCELLED', t.error_code = 'TOOL_RUN_TERMINATED',
                        t.result_summary = '所属运行已停止，工具执行已取消'
                    WHERE t.id = ? AND t.status = 'RUNNING'
                      AND c.owner_user_id = r.owner_user_id
                      AND (r.status <> 'RUNNING' OR r.lease_expires_at IS NULL
                           OR r.lease_expires_at <= CURRENT_TIMESTAMP(3) OR c.deleted <> 0)
                    """, id);
        }
        return recovered;
    }

    @Scheduled(fixedDelayString = "${learnhub.assistant.tool-recovery-delay:10000}")
    public void recoverScheduled() {
        if (!chatEnabled) return;
        try {
            recoverTerminated();
        } catch (RuntimeException failure) {
            log.warn("工具执行状态补偿失败 errorType={}", failure.getClass().getSimpleName());
        }
    }

    private static void validateRun(long owner, Run stored) {
        if (owner <= 0 || stored == null || stored.ownerUserId() != owner
                || !safeId(stored.id()) || !safeId(stored.conversationId())
                || !safeId(stored.leaseOwner())) throw invalid();
    }

    private static void validateExecution(String executionId, long durationMillis) {
        if (!safeId(executionId) || durationMillis < 0 || durationMillis > 300_000) throw invalid();
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static BusinessException invalid() {
        return new BusinessException("CHAT_INPUT_INVALID", "工具执行记录参数无效");
    }

    private static BusinessException cancelled() {
        return new BusinessException("ASSISTANT_CANCELLED", "运行已停止或工具记录已终结");
    }
}
