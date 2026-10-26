package com.learnhub.assistant.knowledge.vector;

import com.learnhub.assistant.knowledge.KnowledgeSearchGateway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 私有 PG 池不作为 Spring DataSource Bean，MySQL 仍由 Boot 正常自动配置。 */
public final class CourseVectorRuntime implements KnowledgeVectorSearch, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CourseVectorRuntime.class);
    private final VectorProjectionSync sync;
    private final boolean hybridEnabled;
    private final boolean syncEnabled;
    private final String url;
    private final String username;
    private final String password;
    private final java.util.function.Supplier<CourseEmbedding> embeddingFactory;
    private CourseEmbedding embedding;
    private HikariDataSource pool;
    private CourseVectorIndex index;
    private boolean closed;

    public CourseVectorRuntime(VectorProjectionSync sync, boolean hybridEnabled, boolean syncEnabled,
                               String url, String username, String password) {
        this(sync, hybridEnabled, syncEnabled, url, username, password, BgeChineseEmbedding::new);
    }

    public CourseVectorRuntime(VectorProjectionSync sync, boolean hybridEnabled, boolean syncEnabled,
                               String url, String username, String password,
                               java.util.function.Supplier<CourseEmbedding> embeddingFactory) {
        if (url == null || !url.startsWith("jdbc:postgresql://") || username == null || username.isBlank()
                || password == null || password.isBlank()) throw new IllegalArgumentException("Vector local configuration required");
        this.sync = sync; this.hybridEnabled = hybridEnabled; this.syncEnabled = syncEnabled;
        this.url = url; this.username = username; this.password = password;
        this.embeddingFactory = java.util.Objects.requireNonNull(embeddingFactory);
    }

    @Override public boolean hybridEnabled() { return hybridEnabled; }

    @Override public List<KnowledgeSearchGateway.Hit> search(long courseId, List<Long> readableLessonIds,
                                                           String question, int limit) {
        var versions = sync.currentVersions(courseId, readableLessonIds);
        if (versions.isEmpty()) return List.of();
        return index().search(courseId, readableLessonIds, versions, question, limit);
    }

    @Scheduled(fixedDelayString = "${learnhub.vector.sync-delay:10000}")
    public void syncDue() {
        if (!syncEnabled) return;
        try { sync.syncDue(index(), 10); }
        catch (RuntimeException failure) {
            // 不能记录 JDBC URL、凭据、SQL 参数或正文。
            log.warn("向量投影补偿暂不可用 errorType={}", failure.getClass().getSimpleName());
        }
    }

    private synchronized CourseVectorIndex index() {
        if (closed) throw new IllegalStateException("Vector runtime closed");
        if (index == null) {
            if (embedding == null) embedding = embeddingFactory.get();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
            config.setPoolName("learnhub-vector-private");
            config.setMaximumPoolSize(3); config.setMinimumIdle(0);
            config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
            config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("currentSchema", "learnhub_rag,public");
            config.addDataSourceProperty("connectTimeout", "2");
            config.addDataSourceProperty("socketTimeout", "5");
            pool = new HikariDataSource(config);
            try { index = new CourseVectorIndex(pool, embedding); }
            catch (RuntimeException | Error failure) { pool.close(); pool = null; throw failure; }
        }
        return index;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pool != null) pool.close();
        if (embedding != null) embedding.close();
    }
}
