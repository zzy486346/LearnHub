package com.learnhub.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.search.CourseIndexSourceMapper.CourseIndexSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CourseIndexSyncService {
    private static final Logger log = LoggerFactory.getLogger(CourseIndexSyncService.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final CourseIndexTaskMapper taskMapper;
    private final CourseIndexSourceMapper sourceMapper;
    private final CourseSearchGateway gateway;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final String lockName;

    public CourseIndexSyncService(CourseIndexTaskMapper taskMapper,
                                  CourseIndexSourceMapper sourceMapper,
                                  CourseSearchGateway gateway,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${learnhub.search.sync-batch-size:100}") int batchSize) {
        this.taskMapper = taskMapper;
        this.sourceMapper = sourceMapper;
        this.gateway = gateway;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        this.lockName = ("learnhub:search:" + gateway.aliasName()).substring(
                0, Math.min(64, ("learnhub:search:" + gateway.aliasName()).length()));
    }

    public int syncDue(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 1000));
        return withAdvisoryLock(() -> syncDueLocked(limit), () -> 0, 0);
    }

    public RebuildResult adminFullRebuild() {
        return withAdvisoryLock(() -> {
            int indexed = rebuildLocked();
            int drained = syncDueLocked(1000);
            return new RebuildResult(indexed, drained);
        }, () -> { throw new BusinessException("SEARCH_SYNC_BUSY", "搜索索引同步任务正在执行，请稍后重试"); }, 5);
    }

    private int syncDueLocked(int limit) {
        if (!gateway.ensureAlias()) {
            int rebuilt = rebuildLocked();
            log.info("搜索别名不存在，已从 MySQL 重建 {} 条课程文档", rebuilt);
        }
        List<CourseIndexTaskEntity> tasks = taskMapper.selectDue(limit);
        int completed = 0;
        for (CourseIndexTaskEntity task : tasks) {
            try {
                syncOne(task.getCourseId(), gateway.aliasName());
                completed += taskMapper.deleteIfGeneration(task.getCourseId(), task.getGeneration());
            } catch (RuntimeException error) {
                int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
                taskMapper.failIfGeneration(task.getCourseId(), task.getGeneration(),
                        LocalDateTime.now().plusSeconds(retryDelaySeconds(attempts + 1)), summarize(error));
                log.warn("课程搜索索引同步失败，courseId={} generation={}",
                        task.getCourseId(), task.getGeneration(), error);
            }
        }
        return completed;
    }

    private int rebuildLocked() {
        String newIndex = gateway.createVersionedIndex();
        int indexed = 0;
        try {
            long afterId = 0L;
            while (true) {
                List<CourseIndexSource> page = sourceMapper.selectPageAfterId(afterId, batchSize);
                if (page.isEmpty()) break;
                for (CourseIndexSource source : page) {
                    afterId = source.getId();
                    if (isSearchable(source)) {
                        gateway.index(newIndex, toDocument(source));
                        indexed++;
                    }
                }
                if (page.size() < batchSize) break;
            }
            gateway.refresh(newIndex);
            gateway.swapAlias(newIndex);
            return indexed;
        } catch (RuntimeException error) {
            try {
                gateway.deleteIndex(newIndex);
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private void syncOne(Long courseId, String index) {
        CourseIndexSource source = sourceMapper.selectByCourseId(courseId);
        if (source == null || !isSearchable(source)) {
            gateway.delete(courseId);
            return;
        }
        if (index.equals(gateway.aliasName())) gateway.upsert(toDocument(source));
        else gateway.index(index, toDocument(source));
    }

    private boolean isSearchable(CourseIndexSource source) {
        return "PUBLISHED".equals(source.getStatus()) && !Boolean.TRUE.equals(source.getDeleted());
    }

    private CourseSearchDocument toDocument(CourseIndexSource source) {
        CourseSearchDocument document = new CourseSearchDocument(source.getId(), source.getTitle(),
                source.getDescription(), source.getInstructor(), source.getCoverUrl(), tags(source.getTagJson()),
                source.getLikeCount() == null ? 0L : source.getLikeCount());
        document.setStatus(source.getStatus());
        document.setDeleted(Boolean.TRUE.equals(source.getDeleted()));
        if (source.getPublishedAt() != null) {
            document.setPublishedAt(source.getPublishedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        return document;
    }

    private Set<String> tags(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            Set<String> tags = new LinkedHashSet<>();
            JsonNode values = objectMapper.readTree(json);
            values.forEach(value -> {
                String tag = value.asText().trim();
                if (!tag.isEmpty()) tags.add(tag);
            });
            return Set.copyOf(tags);
        } catch (Exception error) {
            throw new IllegalStateException("课程标签 JSON 解析失败", error);
        }
    }

    private long retryDelaySeconds(int attempts) {
        return Math.min(3600L, 5L * (1L << Math.min(Math.max(0, attempts - 1), 9)));
    }

    private String summarize(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.substring(0, Math.min(MAX_ERROR_LENGTH, message.length()));
    }

    private <T> T withAdvisoryLock(Supplier<T> action, Supplier<T> unavailable, int waitSeconds) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            if (!acquire(connection, waitSeconds)) return unavailable.get();
            try {
                return action.get();
            } finally {
                release(connection);
            }
        });
    }

    private boolean acquire(Connection connection, int waitSeconds) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, waitSeconds);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void release(Connection connection) {
        try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery().close();
        } catch (Exception error) {
            log.error("释放搜索索引同步锁失败，lock={}", lockName, error);
        }
    }

    public record RebuildResult(int indexed, int drainedTasks) {}
}
