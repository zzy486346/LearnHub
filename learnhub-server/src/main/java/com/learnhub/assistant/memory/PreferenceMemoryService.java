package com.learnhub.assistant.memory;

import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** MySQL 是记忆意图事实源，Mem0 候选永远不能自行授权或提供正文。 */
@Service
public class PreferenceMemoryService implements PreferenceMemorySource {
    public static final int MAX_PREFERENCES = 50;
    private final DataSource dataSource;
    private final DeadlineJdbcTemplate jdbc;
    private final CourseService courses;
    private final ObjectProvider<Mem0Client> clients;
    private final boolean enabled;
    private static final RowMapper<PreferenceView> ROW = (rs, n) -> new PreferenceView(
            rs.getString("id"), rs.getLong("version"), rs.getString("category"), rs.getString("text"),
            rs.getString("status"), rs.getString("source_conversation_id"),
            rs.getObject("source_course_id", Long.class), rs.getTimestamp("updated_at").toLocalDateTime());

    public PreferenceMemoryService(DataSource dataSource, CourseService courses, ObjectProvider<Mem0Client> clients,
                                   @Value("${learnhub.assistant.memory.enabled:false}") boolean enabled) {
        this.dataSource = dataSource;
        this.jdbc = new DeadlineJdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(3);
        this.courses = courses;
        this.clients = clients;
        this.enabled = enabled;
    }

    public PreferenceView create(long owner, String category, String text) {
        return create(owner, category, text, null);
    }

    public PreferenceView create(long owner, String category, String text, String sourceConversationId) {
        requireEnabled();
        String clean = PreferencePolicy.validate(category, text);
        Long sourceCourse = sourceConversationId == null ? null : sourceCourse(owner, sourceConversationId);
        String id = UUID.randomUUID().toString();
        // 独立连接级锁覆盖配额检查与落库，远端同步在释放锁后进行。
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            String lockName = "lh-memory-owner:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((connection.getCatalog() + ":" + owner).getBytes(StandardCharsets.UTF_8))).substring(0, 48);
            if (!lock(connection, "SELECT GET_LOCK(?,3)", lockName)) throw unavailable();
            try {
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM assistant_learning_preference WHERE owner_user_id=? AND deleted=0")) {
                    count.setQueryTimeout(3);
                    count.setLong(1, owner);
                    try (ResultSet rows = count.executeQuery()) {
                        if (!rows.next()) throw unavailable();
                        if (rows.getInt(1) >= MAX_PREFERENCES)
                            throw new BusinessException("MEMORY_LIMIT_EXCEEDED", "最多保存50条学习偏好，请先忘记不再需要的偏好");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO assistant_learning_preference
                        (id,owner_user_id,version,category,text,status,source_conversation_id,source_course_id)
                        VALUES (?,?,1,?,?,'PENDING',?,?)
                        """)) {
                    insert.setQueryTimeout(3);
                    insert.setString(1, id); insert.setLong(2, owner); insert.setString(3, category);
                    insert.setString(4, clean); insert.setString(5, sourceConversationId);
                    if (sourceCourse == null) insert.setNull(6, java.sql.Types.BIGINT); else insert.setLong(6, sourceCourse);
                    insert.executeUpdate();
                }
            } finally { lock(connection, "SELECT RELEASE_LOCK(?)", lockName); }
        } catch (BusinessException business) { throw business; }
        catch (Exception failure) { throw unavailable(); }
        synchronize(id);
        return require(owner, id);
    }

    public List<PreferenceView> list(long owner) {
        requireEnabled();
        return jdbc.query("SELECT * FROM assistant_learning_preference WHERE owner_user_id=? AND deleted=0 ORDER BY updated_at DESC,id LIMIT 100", ROW, owner);
    }

    public PreferenceView update(long owner, String id, long expectedVersion, String category, String text) {
        requireEnabled();
        String clean = PreferencePolicy.validate(category, text);
        PreferenceView previous = require(owner, id);
        verifySource(owner, previous);
        if (expectedVersion < 1 || jdbc.update("""
                UPDATE assistant_learning_preference SET version=version+1,category=?,text=?,status='PENDING',
                attempts=0,next_retry_at=CURRENT_TIMESTAMP(3),last_error=NULL
                WHERE id=? AND owner_user_id=? AND version=? AND deleted=0 AND status IN ('PENDING','ACTIVE')
                """, category, clean, id, owner, expectedVersion) != 1) throw conflict();
        synchronize(id);
        return require(owner, id);
    }

    public PreferenceView forget(long owner, String id, long expectedVersion) {
        requireEnabled();
        require(owner, id);
        if (expectedVersion < 1 || jdbc.update("""
                UPDATE assistant_learning_preference SET version=version+1,status='DELETING',text='',deleted=1,
                attempts=0,next_retry_at=CURRENT_TIMESTAMP(3),last_error=NULL
                WHERE id=? AND owner_user_id=? AND version=? AND deleted=0
                """, id, owner, expectedVersion) != 1) throw conflict();
        synchronize(id);
        return find(owner, id, true);
    }

    @Override public List<PreferenceSnapshot> context(long owner, String query, int limit) {
        if (!enabled || query == null || query.isBlank() || query.length() > 500 || limit < 1) return List.of();
        try {
            Mem0Client client = clients.getIfAvailable();
            if (client == null) return List.of();
            List<PreferenceSnapshot> result = new ArrayList<>();
            var seen = new HashSet<String>();
            for (Mem0Client.Hit hit : client.search(owner, query, 12).stream().limit(12).toList()) {
                if (result.size() >= Math.min(3, limit)) break;
                if (hit == null || !seen.add(hit.preferenceId())) continue;
                try {
                    PreferenceSnapshot snapshot = current(owner, hit.preferenceId());
                    if (snapshot.version() == hit.version()) result.add(snapshot);
                } catch (BusinessException unavailable) { /* 当前偏好不再授权。 */ }
            }
            return List.copyOf(result);
        } catch (RuntimeException unavailable) { return List.of(); }
    }

    @Override public PreferenceSnapshot current(long owner, String id) {
        if (!enabled) throw changed();
        PreferenceView value = require(owner, id);
        if (!"ACTIVE".equals(value.status())) throw changed();
        verifySource(owner, value);
        return new PreferenceSnapshot(value.id(), value.version(), value.category(), value.text());
    }

    /** 连接级锁覆盖远端操作，但不持有 MySQL 事务；应用重启后继续当前意图。 */
    @Scheduled(fixedDelayString = "${learnhub.assistant.memory.sync-delay-ms:30000}")
    public void syncDue() {
        if (!enabled) return;
        try {
            for (String id : jdbc.queryForList("""
                    SELECT id FROM assistant_learning_preference WHERE status IN ('PENDING','DELETING')
                    AND next_retry_at<=CURRENT_TIMESTAMP(3) ORDER BY next_retry_at,id LIMIT 20
                    """, String.class)) synchronize(id);
        } catch (RuntimeException unavailable) { /* 无长期记忆降级，不记录正文或异常内容。 */ }
    }

    private void synchronize(String id) {
        try (Connection connection = dataSource.getConnection()) {
            String database = connection.getCatalog();
            String lock = "learnhub-memory:" + database;
            if (lock.length() > 64) throw new IllegalStateException("Memory database lock name too long");
            if (!lock(connection, "SELECT GET_LOCK(?,0)", lock)) return;
            try {
                var rows = jdbc.query("SELECT * FROM assistant_learning_preference WHERE id=? AND status IN ('PENDING','DELETING') AND next_retry_at<=CURRENT_TIMESTAMP(3)", ROW, id);
                if (rows.isEmpty()) return;
                PreferenceView value = rows.get(0);
                Long owner = jdbc.queryForObject("SELECT owner_user_id FROM assistant_learning_preference WHERE id=?", Long.class, id);
                try {
                    Mem0Client client = clients.getIfAvailable();
                    if (client == null) throw new IllegalStateException("Memory client unavailable");
                    boolean deleting = "DELETING".equals(value.status());
                    if (deleting) client.delete(owner, id, value.version());
                    else {
                        // 补偿也会重新发送正文，不能复用创建时的来源权限。
                        verifySource(owner, value);
                        client.upsert(owner, id, value.version(), value.category(), value.text());
                    }
                    jdbc.update("""
                            UPDATE assistant_learning_preference SET status=?,attempts=0,last_error=NULL
                            WHERE id=? AND version=? AND status=?
                            """, deleting ? "DELETED" : "ACTIVE", id, value.version(), value.status());
                } catch (RuntimeException unavailable) {
                    jdbc.update("""
                            UPDATE assistant_learning_preference SET next_retry_at=TIMESTAMPADD(SECOND,
                            LEAST(3600,30*POW(2,LEAST(attempts,7))),CURRENT_TIMESTAMP(3)),
                            attempts=LEAST(attempts+1,1000000),last_error='MEMORY_UNAVAILABLE'
                            WHERE id=? AND version=? AND status=?
                            """, id, value.version(), value.status());
                }
            } finally { lock(connection, "SELECT RELEASE_LOCK(?)", lock); }
        } catch (Exception unavailable) { /* 意图仍在 MySQL，下轮补偿。 */ }
    }

    private static boolean lock(Connection connection, String sql, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(3);
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() && rs.getInt(1) == 1; }
        }
    }

    private PreferenceView require(long owner, String id) { return find(owner, id, false); }
    private PreferenceView find(long owner, String id, boolean includeDeleted) {
        uuid(id);
        var rows = jdbc.query("SELECT * FROM assistant_learning_preference WHERE owner_user_id=? AND id=?"
                + (includeDeleted ? "" : " AND deleted=0"), ROW, owner, id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    private Long sourceCourse(long owner, String id) {
        uuid(id);
        var rows = jdbc.queryForList("SELECT course_id FROM assistant_conversation WHERE id=? AND owner_user_id=? AND deleted=0 AND status='ACTIVE'", Long.class, id, owner);
        if (rows.size() != 1) throw missing();
        courses.requireAvailable(rows.get(0), owner);
        return rows.get(0);
    }
    private void verifySource(long owner, PreferenceView value) {
        if (value.sourceConversationId() != null
                && !sourceCourse(owner, value.sourceConversationId()).equals(value.sourceCourseId())) throw changed();
    }
    private static void uuid(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw missing(); }
        catch (IllegalArgumentException invalid) { throw missing(); }
    }
    private void requireEnabled() {
        if (!enabled) throw new BusinessException("MEMORY_DISABLED", "长期学习偏好功能未启用");
    }
    private static BusinessException missing() { return new BusinessException("MEMORY_NOT_FOUND", "学习偏好不存在"); }
    private static BusinessException conflict() { return new BusinessException("MEMORY_VERSION_CONFLICT", "偏好已变化，请刷新后重试"); }
    private static BusinessException changed() { return new BusinessException("MEMORY_CHANGED", "学习偏好已变化或不可用"); }
    private static BusinessException unavailable() { return new BusinessException("MEMORY_UNAVAILABLE", "学习偏好服务暂时不可用"); }

    public record PreferenceView(String id, long version, String category, String text, String status,
                                 String sourceConversationId, Long sourceCourseId, LocalDateTime updatedAt) { }
}
