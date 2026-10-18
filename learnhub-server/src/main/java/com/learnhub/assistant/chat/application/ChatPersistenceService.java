package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatPersistenceService {
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SOURCES = 50;
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);
    private static final AtomicLong LAST_CONVERSATION_TICK = new AtomicLong();
    private static final RowMapper<Conversation> CONVERSATION_MAPPER = ChatPersistenceService::mapConversation;
    private static final RowMapper<Run> RUN_MAPPER = ChatPersistenceService::mapRun;
    private static final RowMapper<Message> MESSAGE_MAPPER = ChatPersistenceService::mapMessage;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ChatPersistenceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(jdbc, "jdbc");
        this.jdbc = new DeadlineJdbcTemplate(Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource"));
        this.jdbc.setQueryTimeout(10);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    public Conversation createConversation(long owner, long course, String title) {
        requirePositive(owner, "用户编号");
        requirePositive(course, "课程编号");
        String normalizedTitle = normalized(title, 120, "会话标题");
        String id = conversationId();
        jdbc.update("""
                INSERT INTO assistant_conversation
                    (id, owner_user_id, course_id, title, status, next_seq, version, deleted)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, 0, 0)
                """, id, owner, course, normalizedTitle);
        return conversation(owner, id);
    }

    public Conversation conversation(long owner, String id) {
        return ownedConversation(owner, id, false);
    }

    public List<Conversation> conversations(long owner, int offset, int limit) {
        requirePositive(owner, "用户编号");
        if (offset < 0 || limit < 1 || limit > 100) {
            throw invalid("分页参数无效");
        }
        return jdbc.query("""
                SELECT id, owner_user_id, course_id, title, next_seq
                FROM assistant_conversation
                WHERE owner_user_id = ? AND deleted = 0
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, CONVERSATION_MAPPER, owner, limit, offset);
    }

    public void rename(long owner, String id, String title) {
        String normalizedTitle = normalized(title, 120, "会话标题");
        int changed = jdbc.update("""
                UPDATE assistant_conversation
                SET title = ?, version = version + 1
                WHERE id = ? AND owner_user_id = ? AND deleted = 0
                """, normalizedTitle, requiredId(id), owner);
        if (changed == 0) throw notFound();
    }

    public List<String> delete(long owner, String id) {
        return transactions.execute(status -> {
            Conversation conversation = ownedConversation(owner, id, true);
            List<String> activeRuns = jdbc.queryForList("""
                    SELECT id FROM assistant_run
                    WHERE conversation_id = ? AND owner_user_id = ? AND status = 'RUNNING'
                    ORDER BY created_at, id
                    """, String.class, conversation.id(), owner);
            jdbc.update("""
                    UPDATE assistant_run
                    SET status = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                        error_code = 'CONVERSATION_DELETED', completed_at = CURRENT_TIMESTAMP(3)
                    WHERE conversation_id = ? AND owner_user_id = ? AND status = 'RUNNING'
                    """, conversation.id(), owner);
            int changed = jdbc.update("""
                    UPDATE assistant_conversation
                    SET status = 'DELETED', deleted = 1, version = version + 1
                    WHERE id = ? AND owner_user_id = ? AND deleted = 0
                    """, conversation.id(), owner);
            if (changed != 1) throw notFound();
            return List.copyOf(activeRuns);
        });
    }

    public Started start(long owner, String conversationId, String clientRequestId,
                         String message, Duration lease) {
        String normalizedConversationId = requiredId(conversationId);
        String normalizedRequestId = normalized(clientRequestId, 64, "客户端请求编号");
        String normalizedMessage = normalized(message, 500, "用户消息");
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw invalid("运行租约须为正数且不能超过五分钟");
        }
        String requestHash = sha256(normalizedConversationId + "\n" + normalizedMessage);
        return transactions.execute(status -> {
            Conversation conversation = ownedConversation(owner, normalizedConversationId, true);
            Run existing = findByClientRequest(owner, normalizedRequestId);
            if (existing != null) return idempotent(existing, requestHash);

            Instant now = Instant.now();
            jdbc.update("""
                    UPDATE assistant_run
                    SET status = 'INTERRUPTED', lease_owner = NULL, lease_expires_at = NULL,
                        error_code = 'LEASE_EXPIRED', completed_at = CURRENT_TIMESTAMP(3)
                    WHERE conversation_id = ? AND owner_user_id = ? AND status = 'RUNNING'
                      AND lease_expires_at <= CURRENT_TIMESTAMP(3)
                    """, conversation.id(), owner);
            Integer busy = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM assistant_run
                    WHERE conversation_id = ? AND owner_user_id = ? AND status = 'RUNNING'
                      AND lease_expires_at > CURRENT_TIMESTAMP(3)
                    """, Integer.class, conversation.id(), owner);
            if (busy != null && busy > 0) {
                throw new BusinessException("CHAT_BUSY", "该会话正在生成回答");
            }

            String runId = uuid();
            String leaseOwner = uuid();
            String messageId = uuid();
            try {
                jdbc.update("""
                        INSERT INTO assistant_run
                            (id, conversation_id, owner_user_id, client_request_id, request_hash,
                             status, lease_owner, lease_expires_at, message, user_message_id)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)
                        """, runId, conversation.id(), owner, normalizedRequestId, requestHash,
                        leaseOwner, Timestamp.from(now.plus(lease)), normalizedMessage, messageId);
            } catch (DuplicateKeyException duplicate) {
                Run concurrent = findByClientRequest(owner, normalizedRequestId);
                if (concurrent == null) throw duplicate;
                return idempotent(concurrent, requestHash);
            }
            jdbc.update("""
                    INSERT INTO assistant_message
                        (id, conversation_id, seq, role, content, status, run_id, sources_json)
                    VALUES (?, ?, ?, 'USER', ?, 'COMPLETED', ?, NULL)
                    """, messageId, conversation.id(), conversation.nextSeq(), normalizedMessage, runId);
            int advanced = jdbc.update("""
                    UPDATE assistant_conversation
                    SET next_seq = next_seq + 1, version = version + 1
                    WHERE id = ? AND owner_user_id = ? AND deleted = 0 AND next_seq = ?
                    """, conversation.id(), owner, conversation.nextSeq());
            if (advanced != 1) throw new IllegalStateException("会话消息序号分配失败");
            return new Started(new Run(runId, conversation.id(), owner, normalizedRequestId,
                    RUNNING, leaseOwner, normalizedMessage), true);
        });
    }

    public boolean complete(long owner, String runId, String leaseOwner, String content,
                            String sourcesJson, String usageJson) {
        String normalizedContent = normalized(content, 8_192, "助手回答");
        String normalizedSources = normalizeSources(sourcesJson);
        String normalizedUsage = normalizeJsonObject(usageJson, "模型用量");
        List<Source> sources = parseSources(normalizedSources);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            RunState snapshot = ownedRunState(owner, runId, false);
            Conversation conversation;
            try {
                conversation = ownedConversation(owner, snapshot.conversationId(), true);
            } catch (BusinessException missing) {
                return false;
            }
            RunState run = ownedRunState(owner, runId, true);
            if (!RUNNING.equals(run.status()) || !Objects.equals(run.leaseOwner(), leaseOwner)
                    || run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(Instant.now())) {
                return false;
            }
            String messageId = uuid();
            int completed = jdbc.update("""
                    UPDATE assistant_run r
                    SET r.status = 'COMPLETED', r.usage_json = ?, r.lease_owner = NULL,
                        r.lease_expires_at = NULL, r.assistant_message_id = ?,
                        r.completed_at = CURRENT_TIMESTAMP(3)
                    WHERE r.id = ? AND r.owner_user_id = ? AND r.status = 'RUNNING'
                      AND r.lease_owner = ? AND r.lease_expires_at > CURRENT_TIMESTAMP(3)
                      AND EXISTS (SELECT 1 FROM assistant_conversation c
                                  WHERE c.id = r.conversation_id AND c.owner_user_id = r.owner_user_id
                                    AND c.deleted = 0)
                    """, normalizedUsage, messageId, run.id(), owner, leaseOwner);
            if (completed != 1) return false;

            jdbc.update("""
                    INSERT INTO assistant_message
                        (id, conversation_id, seq, role, content, status, run_id, sources_json)
                    VALUES (?, ?, ?, 'ASSISTANT', ?, 'COMPLETED', ?, ?)
                    """, messageId, conversation.id(), conversation.nextSeq(), normalizedContent,
                    run.id(), normalizedSources);
            insertSources(messageId, sources);
            int advanced = jdbc.update("""
                    UPDATE assistant_conversation
                    SET next_seq = next_seq + 1, version = version + 1
                    WHERE id = ? AND owner_user_id = ? AND deleted = 0 AND next_seq = ?
                    """, conversation.id(), owner, conversation.nextSeq());
            if (advanced != 1) throw new IllegalStateException("会话消息序号分配失败");
            return true;
        }));
    }

    public boolean fail(long owner, String runId, String status, String errorCode) {
        String terminal = requiredTerminal(status);
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            RunState snapshot = ownedRunState(owner, runId, false);
            ownedConversationAny(owner, snapshot.conversationId(), true);
            RunState current = ownedRunState(owner, runId, true);
            if (!RUNNING.equals(current.status())) return false;
            return jdbc.update("""
                    UPDATE assistant_run
                    SET status = ?, error_code = ?, lease_owner = NULL, lease_expires_at = NULL,
                        completed_at = CURRENT_TIMESTAMP(3)
                    WHERE id = ? AND owner_user_id = ? AND status = 'RUNNING'
                    """, terminal, nullable(errorCode, 64), current.id(), owner) == 1;
        }));
    }

    public Run cancel(long owner, String runId) {
        return transactions.execute(status -> {
            RunState snapshot = ownedRunState(owner, runId, false);
            ownedConversationAny(owner, snapshot.conversationId(), true);
            RunState current = ownedRunState(owner, runId, true);
            if (RUNNING.equals(current.status())) {
                jdbc.update("""
                        UPDATE assistant_run
                        SET status = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                            error_code = 'USER_CANCELLED', completed_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND owner_user_id = ? AND status = 'RUNNING'
                        """, current.id(), owner);
            }
            return run(owner, current.id());
        });
    }

    public Run run(long owner, String runId) {
        List<Run> runs = jdbc.query("""
                SELECT id, conversation_id, owner_user_id, client_request_id, status, lease_owner, message
                FROM assistant_run WHERE id = ? AND owner_user_id = ?
                """, RUN_MAPPER, requiredId(runId), owner);
        if (runs.isEmpty()) throw notFound();
        return runs.get(0);
    }

    public List<Message> messages(long owner, String conversationId, long beforeSeq, int limit) {
        Conversation conversation = ownedConversation(owner, conversationId, false);
        if (limit < 1 || limit > 100) throw invalid("消息分页参数无效");
        long cursor = beforeSeq <= 0 ? Long.MAX_VALUE : beforeSeq;
        return jdbc.query("""
                SELECT id, seq, role, content, status, run_id, sources_json
                FROM (
                    SELECT id, seq, role, content, status, run_id, sources_json
                    FROM assistant_message
                    WHERE conversation_id = ? AND seq < ?
                    ORDER BY seq DESC
                    LIMIT ?
                ) recent
                ORDER BY seq ASC
                """, MESSAGE_MAPPER, conversation.id(), cursor, limit);
    }

    public int recoverExpired() {
        List<RunKey> expired = jdbc.query("""
                SELECT id, conversation_id, owner_user_id
                FROM assistant_run
                WHERE status = 'RUNNING' AND lease_expires_at <= CURRENT_TIMESTAMP(3)
                ORDER BY conversation_id, id
                LIMIT 100
                """, (rs, row) -> new RunKey(rs.getString("id"), rs.getString("conversation_id"),
                rs.getLong("owner_user_id")));
        int recovered = 0;
        for (RunKey candidate : expired) {
            Boolean changed = transactions.execute(tx -> {
                ownedConversationAny(candidate.ownerUserId(), candidate.conversationId(), true);
                RunState current = ownedRunState(candidate.ownerUserId(), candidate.id(), true);
                if (!RUNNING.equals(current.status()) || current.leaseExpiresAt() == null
                        || current.leaseExpiresAt().isAfter(Instant.now())) return false;
                return jdbc.update("""
                        UPDATE assistant_run
                        SET status = 'INTERRUPTED', lease_owner = NULL, lease_expires_at = NULL,
                            error_code = 'LEASE_EXPIRED', completed_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND owner_user_id = ? AND status = 'RUNNING'
                          AND lease_expires_at <= CURRENT_TIMESTAMP(3)
                        """, candidate.id(), candidate.ownerUserId()) == 1;
            });
            if (Boolean.TRUE.equals(changed)) recovered++;
        }
        return recovered;
    }

    private Conversation ownedConversation(long owner, String id, boolean lock) {
        return ownedConversation(owner, id, lock, true);
    }

    private Conversation ownedConversationAny(long owner, String id, boolean lock) {
        return ownedConversation(owner, id, lock, false);
    }

    private Conversation ownedConversation(long owner, String id, boolean lock, boolean activeOnly) {
        requirePositive(owner, "用户编号");
        String sql = """
                SELECT id, owner_user_id, course_id, title, next_seq
                FROM assistant_conversation
                WHERE id = ? AND owner_user_id = ?
                """ + (activeOnly ? " AND deleted = 0" : "") + (lock ? " FOR UPDATE" : "");
        List<Conversation> conversations = jdbc.query(sql, CONVERSATION_MAPPER, requiredId(id), owner);
        if (conversations.isEmpty()) throw notFound();
        return conversations.get(0);
    }

    private RunState ownedRunState(long owner, String runId, boolean lock) {
        String sql = """
                SELECT id, conversation_id, status, lease_owner, lease_expires_at, request_hash
                FROM assistant_run WHERE id = ? AND owner_user_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<RunState> rows = jdbc.query(sql, (rs, row) -> new RunState(
                rs.getString("id"), rs.getString("conversation_id"), rs.getString("status"),
                rs.getString("lease_owner"), instant(rs, "lease_expires_at"), rs.getString("request_hash")),
                requiredId(runId), owner);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    private Run findByClientRequest(long owner, String clientRequestId) {
        List<Run> rows = jdbc.query("""
                SELECT id, conversation_id, owner_user_id, client_request_id, status, lease_owner, message
                FROM assistant_run WHERE owner_user_id = ? AND client_request_id = ?
                """, RUN_MAPPER, owner, clientRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Started idempotent(Run existing, String requestHash) {
        String stored = jdbc.queryForObject("SELECT request_hash FROM assistant_run WHERE id = ?",
                String.class, existing.id());
        if (!Objects.equals(stored, requestHash)) {
            throw new BusinessException("CHAT_IDEMPOTENCY_CONFLICT", "请求编号已用于不同的会话或问题");
        }
        return new Started(existing, false);
    }

    private void insertSources(String messageId, List<Source> sources) {
        for (Source source : sources) {
            jdbc.update("""
                    INSERT INTO assistant_message_source
                        (id, message_id, document_id, chunk_id, material_version, source_kind, citation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, uuid(), messageId, source.documentId(), source.chunkId(), source.materialVersion(),
                    source.sourceKind(), source.citationId());
        }
    }

    private static List<Source> parseSources(String json) {
        try {
            JsonNode root = strictJson(json);
            if (!root.isArray() || root.size() > MAX_SOURCES) throw invalid("回答来源格式无效");
            List<Source> sources = new ArrayList<>(root.size());
            int index = 0;
            for (JsonNode node : root) {
                if (!node.isObject()) throw invalid("回答来源格式无效");
                Long documentId = positiveLong(node.get("documentId"));
                Long chunkId = positiveLong(node.get("chunkId"));
                Long version = positiveLong(node.get("sourceVersion"));
                if (documentId == null || chunkId == null || version == null) {
                    throw invalid("回答来源缺少文档、片段或材料版本标识");
                }
                sources.add(new Source(documentId, chunkId, version, "MATERIAL", "E" + (++index)));
            }
            return List.copyOf(sources);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("回答来源不是有效的 JSON 数组");
        }
    }

    private static String normalizeSources(String json) {
        String value = json == null || json.isBlank() ? "[]" : json.trim();
        if (value.length() > 100_000) throw invalid("回答来源过大");
        parseSources(value);
        return value;
    }

    private static String normalizeJsonObject(String json, String label) {
        if (json == null || json.isBlank()) return null;
        String value = json.trim();
        if (value.length() > 20_000) throw invalid(label + "过大");
        try {
            if (!strictJson(value).isObject()) throw invalid(label + "格式无效");
            return value;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw invalid(label + "不是有效的 JSON 对象");
        }
    }

    private static Conversation mapConversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getLong("owner_user_id"), rs.getLong("course_id"),
                rs.getString("title"), rs.getLong("next_seq"));
    }

    private static Run mapRun(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getString("id"), rs.getString("conversation_id"), rs.getLong("owner_user_id"),
                rs.getString("client_request_id"), rs.getString("status"), rs.getString("lease_owner"),
                rs.getString("message"));
    }

    private static Message mapMessage(ResultSet rs, int row) throws SQLException {
        return new Message(rs.getString("id"), rs.getLong("seq"), rs.getString("role"),
                rs.getString("content"), rs.getString("status"), rs.getString("run_id"),
                rs.getString("sources_json"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String requiredTerminal(String status) {
        if (!FAILED.equals(status) && !CANCELLED.equals(status) && !INTERRUPTED.equals(status)) {
            throw invalid("运行失败状态无效");
        }
        return status;
    }

    private static String normalized(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) throw invalid(label + "无效");
        return normalized;
    }

    private static String nullable(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw invalid("错误编码过长");
        return normalized;
    }

    private static String requiredId(String value) {
        return normalized(value, 64, "记录编号");
    }

    private static void requirePositive(long value, String label) {
        if (value <= 0) throw invalid(label + "须为正数");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("CHAT_INPUT_INVALID", message);
    }

    private static BusinessException notFound() {
        return new BusinessException("CHAT_NOT_FOUND", "会话或运行不存在");
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String conversationId() {
        long tick = LAST_CONVERSATION_TICK.updateAndGet(previous ->
                Math.max(System.currentTimeMillis(), previous + 1));
        return "%016x-%s".formatted(tick, UUID.randomUUID().toString().replace("-", ""));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static Long positiveLong(JsonNode node) {
        if (node == null) return null;
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            long value = node.longValue();
            return value > 0 ? value : null;
        }
        if (!node.isTextual() || !node.textValue().matches("[1-9][0-9]{0,18}")) return null;
        try {
            long value = Long.parseLong(node.textValue());
            return value > 0 ? value : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    private static JsonNode strictJson(String value) throws java.io.IOException {
        return JSON.reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(value);
    }

    public record Conversation(String id, long ownerUserId, long courseId, String title, long nextSeq) { }
    public record Run(String id, String conversationId, long ownerUserId, String clientRequestId,
                      String status, String leaseOwner, String message) { }
    public record Started(Run run, boolean created) { }
    public record Message(String id, long seq, String role, String content, String status,
                          String runId, String sourcesJson) { }

    private record RunState(String id, String conversationId, String status, String leaseOwner,
                            Instant leaseExpiresAt, String requestHash) { }
    private record Source(long documentId, long chunkId, long materialVersion,
                          String sourceKind, String citationId) { }
    private record RunKey(String id, String conversationId, long ownerUserId) { }
}
