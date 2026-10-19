package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.*;
import com.learnhub.assistant.knowledge.KnowledgeChunkRecord;
import com.learnhub.assistant.knowledge.KnowledgeDocumentRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "learnhub.vector.it.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CourseVectorInfrastructureIntegrationTest {
    private BgeChineseEmbedding embedding;
    private CourseVectorIndex index;
    private JdbcTemplate admin;
    private JdbcTemplate app;

    @BeforeAll void initializeOwnedDatabase() throws Exception {
        String port = System.getenv("LEARNHUB_VECTOR_IT_PORT");
        if (port == null || !port.matches("[0-9]+")) throw new IllegalStateException("Owned test port required");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/learnhub_vector_it?currentSchema=learnhub_rag,public&connectTimeout=2&socketTimeout=5";
        var adminSource = new DriverManagerDataSource(url, "postgres", System.getenv("LEARNHUB_VECTOR_IT_PASSWORD"));
        admin = new JdbcTemplate(adminSource);
        Path sql = Path.of("../sql/postgres/001_course_embeddings.sql");
        if (!Files.exists(sql)) sql = Path.of("sql/postgres/001_course_embeddings.sql");
        try (var connection = adminSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(Files.readString(sql));
        }
        String password = UUID.randomUUID().toString().replace("-", "");
        try (var connection = adminSource.getConnection(); var statement = connection.createStatement()) {
            // 不让 JdbcTemplate 的异常包装包含带随机密码的 SQL。
            statement.execute("CREATE ROLE learnhub_vector_app LOGIN PASSWORD '" + password + "'");
        }
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        admin.execute("GRANT USAGE ON SCHEMA learnhub_rag, public TO learnhub_vector_app");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON learnhub_rag.course_embeddings_bge_zh_v1 TO learnhub_vector_app");
        var appSource = new DriverManagerDataSource(url, "learnhub_vector_app", password);
        app = new JdbcTemplate(appSource);
        embedding = new BgeChineseEmbedding();
        index = new CourseVectorIndex(appSource, embedding);
    }

    @BeforeEach void emptyProjection() { admin.execute("TRUNCATE learnhub_rag.course_embeddings_bge_zh_v1"); }
    @AfterAll void closeModel() { if (embedding != null) embedding.close(); }

    @Test void realWriteSearchIdempotencyAndServerScopeFiltering() {
        upsert(1, 1, 10, 101, 11, "退款后无法继续观看付费课程");
        upsert(1, 1, 10, 101, 11, "退款后无法继续观看付费课程");
        upsert(2, 1, 20, 201, 21, "退款后无法继续观看付费课程");
        upsert(3, 1, 10, 102, 31, "退款后无法继续观看付费课程");
        upsert(4, 1, 10, 101, 41, "使用鸡蛋和面粉制作蛋糕");
        assertThat(count()).isEqualTo(4);
        var hits = index.search(10, List.of(101L), Map.of(1L, 1L, 2L, 1L, 3L, 1L, 4L, 1L), "退费以后还能看买过的课吗？", 10);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunkId()).isEqualTo(11);
        assertThat(hits).extracting(hit -> hit.documentId()).containsExactlyInAnyOrder(1L, 4L);
        assertThat(index.search(10, List.of(), Map.of(1L, 1L), "课程", 10)).isEmpty();
        assertThat(index.search(30, List.of(301L), Map.of(1L, 1L), "课程", 10)).isEmpty();
        assertThat(index.search(10, List.of(101L), Map.of(), "课程", 10)).isEmpty();
    }

    @Test void olderJobCannotDeleteNewerVersionAndProjectionCanBeRebuilt() {
        upsert(1, 2, 10, 101, 12, "新版本课程资料");
        upsert(1, 1, 10, 101, 11, "迟到的旧版本资料");
        index.removeOlderVersions(1, 1);
        assertThat(count()).isEqualTo(2);
        // 即使旧写入迟到，新版本快照过滤在排序前排除旧投影，不能挤占 top-1。
        assertThat(index.search(10, List.of(101L), Map.of(1L, 2L), "迟到的旧版本资料", 1))
                .hasSize(1).allMatch(hit -> hit.sourceVersion() == 2);
        index.removeOlderVersions(1, 2);
        assertThat(count()).isEqualTo(1);
        assertThat(index.search(10, List.of(101L), Map.of(1L, 2L), "课程资料", 10)).allMatch(hit -> hit.sourceVersion() == 2);
        index.removeDocument(1);
        assertThat(count()).isZero();
        upsert(1, 2, 10, 101, 12, "新版本课程资料");
        assertThat(count()).isEqualTo(1);
    }

    @Test void runtimeRoleHasOnlyDmlAndMigrationIncludesChineseCommentsAndCorrectDimension() {
        assertThatThrownBy(() -> app.execute("CREATE TABLE learnhub_rag.forbidden(id BIGINT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> app.execute("CREATE EXTENSION hstore"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(admin.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute "
                + "WHERE attrelid='learnhub_rag.course_embeddings_bge_zh_v1'::regclass AND attname='embedding'", String.class))
                .isEqualTo("vector(512)");
        assertThat(admin.queryForObject("SELECT COUNT(*) FROM pg_attribute WHERE attrelid="
                + "'learnhub_rag.course_embeddings_bge_zh_v1'::regclass AND attnum>0 AND NOT attisdropped "
                + "AND col_description(attrelid,attnum) IS NOT NULL", Integer.class)).isEqualTo(6);
    }

    @Test void splittingCreatesMultiplePartsButPreservesOriginalKnowledgeChunkIdentity() {
        upsert(1, 1, 10, 101, 11, "课".repeat(600));
        assertThat(count()).isGreaterThan(1);
        assertThat(index.search(10, List.of(101L), Map.of(1L, 1L), "课程", 10))
                .hasSize(1)
                .allMatch(hit -> hit.chunkId() == 11 && hit.documentId() == 1 && hit.sourceVersion() == 1);
        assertThatThrownBy(() -> index.upsert(new KnowledgeDocumentRecord(1, 2, 10, 101, "材料"),
                new KnowledgeChunkRecord(11, 1, 1, 0, "小节", "正文"))).isInstanceOf(IllegalArgumentException.class);
    }

    private void upsert(long doc, long version, long course, long lesson, long chunk, String text) {
        index.upsert(new KnowledgeDocumentRecord(doc, version, course, lesson, "测试材料"),
                new KnowledgeChunkRecord(chunk, doc, version, 0, "小节", text));
    }
    private int count() { return admin.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1", Integer.class); }
}
