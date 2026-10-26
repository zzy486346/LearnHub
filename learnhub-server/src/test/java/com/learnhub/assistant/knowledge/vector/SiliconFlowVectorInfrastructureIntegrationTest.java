package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeChunkRecord;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 仅使用 verify-vector.ps1 创建的隔离 MySQL/PG 和本类回环 Mock。 */
@EnabledIfSystemProperty(named = "learnhub.vector.it.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiliconFlowVectorInfrastructureIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "sk-" + "i".repeat(60);
    private static final long DOCUMENT = 8_100_001L;
    private static final long DENIED_DOCUMENT = 8_100_002L;
    private static final long OTHER_COURSE_DOCUMENT = 8_100_003L;

    private JdbcTemplate mysql;
    private JdbcTemplate pgAdmin;
    private JdbcTemplate pgApp;
    private KnowledgeStore knowledge;
    private VectorProjectionSync sync;
    private CourseVectorIndex index;
    private SiliconFlowBgeEmbedding embedding;
    private HttpServer http;
    private ExecutorService httpWorkers;
    private final AtomicInteger httpRequests = new AtomicInteger();

    @BeforeAll
    void initializeOwnedInfrastructure() throws Exception {
        String mysqlPort = requiredPort("LEARNHUB_VECTOR_IT_MYSQL_PORT");
        String mysqlPassword = requiredPassword("LEARNHUB_VECTOR_IT_MYSQL_PASSWORD");
        var mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + mysqlPort
                + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", mysqlPassword));
        mysqlAdmin.execute("CREATE DATABASE IF NOT EXISTS learnhub_vector_remote_it "
                + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var mysqlSource = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + mysqlPort
                + "/learnhub_vector_remote_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root", mysqlPassword);
        mysql = new JdbcTemplate(mysqlSource);
        String schema = Files.readString(projectFile("sql/schema.sql"))
                .replaceAll("(?im)^CREATE DATABASE[^\\r\\n]*[\\r\\n]*", "")
                .replaceAll("(?im)^USE learnhub;[\\r\\n]*", "");
        executeMysqlScript(mysqlSource, schema.getBytes(StandardCharsets.UTF_8));
        executeMysqlScript(mysqlSource,
                Files.readAllBytes(projectFile("sql/migrations/V20261019__knowledge_vector_projection.sql")));

        String pgPort = requiredPort("LEARNHUB_VECTOR_IT_PORT");
        String pgPassword = requiredPassword("LEARNHUB_VECTOR_IT_PASSWORD");
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pgPort
                + "/learnhub_vector_it?currentSchema=learnhub_rag,public&connectTimeout=2&socketTimeout=5";
        var pgAdminSource = new DriverManagerDataSource(pgUrl, "postgres", pgPassword);
        pgAdmin = new JdbcTemplate(pgAdminSource);
        try (var connection = pgAdminSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(Files.readString(projectFile("sql/postgres/001_course_embeddings.sql")));
            String remoteMigration = Files.readString(
                    projectFile("sql/postgres/002_siliconflow_bge_large_embeddings.sql"));
            statement.execute(remoteMigration);
            statement.execute(remoteMigration);
        }

        String rolePassword = UUID.randomUUID().toString().replace("-", "");
        if (pgAdmin.queryForObject("SELECT COUNT(*) FROM pg_roles WHERE rolname='remote_it_app'", Integer.class) == 0) {
            try (var connection = pgAdminSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE remote_it_app LOGIN PASSWORD '" + rolePassword
                        + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
            }
        } else {
            try (var connection = pgAdminSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER ROLE remote_it_app PASSWORD '" + rolePassword + "'");
            }
        }
        pgAdmin.execute("GRANT USAGE ON SCHEMA learnhub_rag, public TO remote_it_app");
        pgAdmin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "
                + "learnhub_rag.course_embeddings_sf_bge_large_zh_v1 TO remote_it_app");
        var pgAppSource = new DriverManagerDataSource(pgUrl, "remote_it_app", rolePassword);
        pgApp = new JdbcTemplate(pgAppSource);

        httpWorkers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "siliconflow-vector-it-mock");
            thread.setDaemon(true);
            return thread;
        });
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        http.setExecutor(httpWorkers);
        http.createContext("/v1/embeddings", this::embed);
        http.start();

        knowledge = mock(KnowledgeStore.class);
        sync = new VectorProjectionSync(mysql, knowledge, "SILICONFLOW");
        embedding = new SiliconFlowBgeEmbedding(URI.create("http://127.0.0.1:"
                + http.getAddress().getPort() + "/v1/embeddings"), API_KEY, Duration.ofSeconds(3));
        index = new CourseVectorIndex(pgAppSource, embedding);
    }

    @BeforeEach
    void clearOwnedState() {
        mysql.execute("DELETE FROM knowledge_vector_projection");
        mysql.execute("DELETE FROM knowledge_chunk");
        mysql.execute("DELETE FROM knowledge_document");
        pgAdmin.update("DELETE FROM learnhub_rag.course_embeddings_sf_bge_large_zh_v1 "
                + "WHERE metadata->>'documentId' IN (?,?,?)", Long.toString(DOCUMENT),
                Long.toString(DENIED_DOCUMENT), Long.toString(OTHER_COURSE_DOCUMENT));
        pgAdmin.update("DELETE FROM learnhub_rag.course_embeddings_bge_zh_v1 WHERE metadata->>'documentId' IN (?,?,?)",
                Long.toString(DOCUMENT), Long.toString(DENIED_DOCUMENT), Long.toString(OTHER_COURSE_DOCUMENT));
        httpRequests.set(0);
    }

    @AfterAll
    void releaseOwnedResources() throws Exception {
        if (pgAdmin != null) {
            pgAdmin.update("DELETE FROM learnhub_rag.course_embeddings_sf_bge_large_zh_v1 "
                    + "WHERE metadata->>'documentId' IN (?,?,?)", Long.toString(DOCUMENT),
                    Long.toString(DENIED_DOCUMENT), Long.toString(OTHER_COURSE_DOCUMENT));
            pgAdmin.update("DELETE FROM learnhub_rag.course_embeddings_bge_zh_v1 "
                    + "WHERE metadata->>'documentId' IN (?,?,?)", Long.toString(DOCUMENT),
                    Long.toString(DENIED_DOCUMENT), Long.toString(OTHER_COURSE_DOCUMENT));
        }
        if (embedding != null) embedding.close();
        if (http != null) http.stop(0);
        if (httpWorkers != null) {
            httpWorkers.shutdownNow();
            assertThat(httpWorkers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedMigrationCreatesCommented1024TableAndDmlOnlyRole() {
        assertThat(pgAdmin.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute "
                + "WHERE attrelid='learnhub_rag.course_embeddings_sf_bge_large_zh_v1'::regclass "
                + "AND attname='embedding'", String.class)).isEqualTo("vector(1024)");
        assertThat(pgAdmin.queryForObject("SELECT obj_description("
                + "'learnhub_rag.course_embeddings_sf_bge_large_zh_v1'::regclass)", String.class))
                .contains("SiliconFlow", "向量投影");
        assertThat(pgAdmin.queryForList("SELECT col_description(attrelid,attnum) FROM pg_attribute "
                + "WHERE attrelid='learnhub_rag.course_embeddings_sf_bge_large_zh_v1'::regclass "
                + "AND attnum>0 AND NOT attisdropped", String.class))
                .hasSize(6).allMatch(comment -> comment != null && comment.matches(".*[\\p{IsHan}].*"));
        assertThatThrownBy(() -> pgApp.execute("CREATE TABLE learnhub_rag.remote_forbidden(id BIGINT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(pgApp.queryForObject("SELECT COUNT(*) FROM "
                + "learnhub_rag.course_embeddings_sf_bge_large_zh_v1 "
                + "WHERE metadata->>'documentId' IN (?,?,?)", Integer.class, Long.toString(DOCUMENT),
                Long.toString(DENIED_DOCUMENT), Long.toString(OTHER_COURSE_DOCUMENT))).isZero();
    }

    @Test
    void remoteProjectionSearchAndVersionCleanupStayIsolatedFromLocalModel() {
        document(DOCUMENT, 1, 10, 101);
        document(DENIED_DOCUMENT, 1, 10, 102);
        document(OTHER_COURSE_DOCUMENT, 1, 20, 201);
        when(knowledge.chunks(DOCUMENT, 1)).thenReturn(List.of(chunk(8_200_001, DOCUMENT, 1, "退款课程规则")));
        when(knowledge.chunks(DENIED_DOCUMENT, 1)).thenReturn(
                List.of(chunk(8_200_002, DENIED_DOCUMENT, 1, "蛋糕制作课程")));
        when(knowledge.chunks(OTHER_COURSE_DOCUMENT, 1)).thenReturn(
                List.of(chunk(8_200_003, OTHER_COURSE_DOCUMENT, 1, "退款课程规则")));
        mysql.update("INSERT INTO knowledge_vector_projection "
                + "(document_id,source_version,model_id,status,cleanup_pending) VALUES (?,?,?,?,0)",
                DOCUMENT, 1, BgeChineseEmbedding.MODEL_ID, "INDEXED");
        seedLocalProjection(DOCUMENT, 1, 8_200_001);

        assertThat(sync.syncDue(index, 20)).isEqualTo(3);
        assertThat(status(DOCUMENT, 1, SiliconFlowBgeEmbedding.MODEL_ID)).isEqualTo("INDEXED");
        assertThat(status(DOCUMENT, 1, BgeChineseEmbedding.MODEL_ID)).isEqualTo("INDEXED");
        assertThat(sync.currentVersions(10, List.of(101L))).containsExactlyEntriesOf(Map.of(DOCUMENT, 1L));
        assertThat(sync.currentVersions(10, List.of(102L))).containsExactlyEntriesOf(Map.of(DENIED_DOCUMENT, 1L));
        assertThat(sync.currentVersions(20, List.of(201L)))
                .containsExactlyEntriesOf(Map.of(OTHER_COURSE_DOCUMENT, 1L));
        long callsAfterSync = httpRequests.get();
        assertThat(sync.syncDue(index, 20)).isZero();
        assertThat(httpRequests).hasValue((int) callsAfterSync);

        seedWrongModelInRemoteTable();
        var scoped = index.search(10, List.of(101L), Map.of(DOCUMENT, 1L,
                DENIED_DOCUMENT, 1L, OTHER_COURSE_DOCUMENT, 1L), "退费后还能学习吗", 10);
        assertThat(scoped).singleElement().satisfies(hit -> {
            assertThat(hit.documentId()).isEqualTo(DOCUMENT);
            assertThat(hit.chunkId()).isEqualTo(8_200_001L);
            assertThat(hit.sourceVersion()).isEqualTo(1);
        });

        mysql.update("UPDATE knowledge_document SET source_version=2,indexed_version=2 WHERE id=?", DOCUMENT);
        when(knowledge.chunks(DOCUMENT, 2)).thenReturn(List.of(chunk(8_200_011, DOCUMENT, 2, "新版退款课程规则")));
        assertThat(sync.syncDue(index, 20)).isEqualTo(1);
        assertThat(sync.currentVersions(10, List.of(101L))).containsExactlyEntriesOf(Map.of(DOCUMENT, 2L));
        assertThat(pgAdmin.queryForObject("SELECT COUNT(*) FROM "
                + "learnhub_rag.course_embeddings_sf_bge_large_zh_v1 WHERE metadata->>'documentId'=? "
                + "AND metadata->>'sourceVersion'='1' AND metadata->>'modelId'=?", Integer.class,
                Long.toString(DOCUMENT), SiliconFlowBgeEmbedding.MODEL_ID)).isZero();
        assertThat(pgAdmin.queryForObject("SELECT COUNT(*) FROM "
                + "learnhub_rag.course_embeddings_sf_bge_large_zh_v1 WHERE metadata->>'documentId'=? "
                + "AND metadata->>'sourceVersion'='2' AND metadata->>'modelId'=?", Integer.class,
                Long.toString(DOCUMENT), SiliconFlowBgeEmbedding.MODEL_ID)).isOne();
        assertThat(pgAdmin.queryForObject("SELECT COUNT(*) FROM "
                + "learnhub_rag.course_embeddings_sf_bge_large_zh_v1 WHERE metadata->>'documentId'=? "
                + "AND metadata->>'modelId'=?", Integer.class, Long.toString(DOCUMENT),
                BgeChineseEmbedding.MODEL_ID)).isOne();
        assertThat(pgAdmin.queryForObject("SELECT COUNT(*) FROM learnhub_rag.course_embeddings_bge_zh_v1 "
                + "WHERE metadata->>'documentId'=?", Integer.class, Long.toString(DOCUMENT))).isOne();
        assertThat(status(DOCUMENT, 1, BgeChineseEmbedding.MODEL_ID)).isEqualTo("INDEXED");
    }

    private void embed(HttpExchange exchange) throws IOException {
        httpRequests.incrementAndGet();
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        List<Map<String, Object>> data = new ArrayList<>();
        int index = 0;
        for (JsonNode input : body.withArray("input")) {
            String text = input.textValue();
            int coordinate = text.contains("退款") || text.contains("退费") ? 0 : text.contains("蛋糕") ? 1 : 2;
            List<Double> vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
            vector.set(coordinate, 1.0);
            data.add(Map.of("object", "embedding", "index", index++, "embedding", vector));
        }
        byte[] response = JSON.writeValueAsBytes(Map.of("object", "list", "model", SiliconFlowBgeEmbedding.MODEL,
                "data", data, "usage", Map.of("prompt_tokens", data.size(), "total_tokens", data.size())));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void document(long id, long version, long course, long lesson) {
        mysql.update("INSERT INTO knowledge_document "
                + "(id,source_key,title,course_id,lesson_id,content_hash,source_version,indexed_version) "
                + "VALUES (?,?,?,?,?,?,?,?)", id, "remote-owned-" + id, "远程测试材料", course, lesson,
                "a".repeat(64), version, version);
    }

    private static KnowledgeChunkRecord chunk(long id, long document, long version, String text) {
        return new KnowledgeChunkRecord(id, document, version, 0, "隔离小节", text);
    }

    private String status(long document, long version, String modelId) {
        return mysql.queryForObject("SELECT status FROM knowledge_vector_projection "
                + "WHERE document_id=? AND source_version=? AND model_id=?", String.class,
                document, version, modelId);
    }

    private void seedLocalProjection(long document, long version, long chunk) {
        String vector = vectorLiteral(512);
        String metadata = "{\"modelId\":\"" + BgeChineseEmbedding.MODEL_ID + "\",\"documentId\":"
                + document + ",\"sourceVersion\":" + version + ",\"chunkId\":" + chunk
                + ",\"courseId\":10,\"lessonId\":101,\"part\":0}";
        pgAdmin.update("INSERT INTO learnhub_rag.course_embeddings_bge_zh_v1 "
                + "(embedding_id,embedding,text,metadata) VALUES (?,CAST(? AS public.vector),?,CAST(? AS json))",
                UUID.randomUUID(), vector, "本地投影保留测试", metadata);
    }

    private void seedWrongModelInRemoteTable() {
        String metadata = "{\"modelId\":\"" + BgeChineseEmbedding.MODEL_ID + "\",\"documentId\":"
                + DOCUMENT + ",\"sourceVersion\":1,\"chunkId\":8299999,"
                + "\"courseId\":10,\"lessonId\":101,\"part\":0}";
        pgAdmin.update("INSERT INTO learnhub_rag.course_embeddings_sf_bge_large_zh_v1 "
                + "(embedding_id,embedding,text,metadata) VALUES (?,CAST(? AS public.vector),?,CAST(? AS json))",
                UUID.randomUUID(), vectorLiteral(1024), "错误模型身份投影", metadata);
    }

    private static String vectorLiteral(int dimension) {
        StringBuilder value = new StringBuilder("[1");
        for (int i = 1; i < dimension; i++) value.append(",0");
        return value.append(']').toString();
    }

    private static void executeMysqlScript(DriverManagerDataSource source, byte[] script) {
        var populator = new ResourceDatabasePopulator(new ByteArrayResource(script));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(source);
    }

    private static String requiredPort(String key) {
        String value = System.getenv(key);
        if (value == null || !value.matches("[0-9]+")) throw new IllegalStateException("Owned test port required");
        return value;
    }

    private static String requiredPassword(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Owned test credential required");
        return value;
    }

    private static Path projectFile(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("..").resolve(relative);
    }
}
