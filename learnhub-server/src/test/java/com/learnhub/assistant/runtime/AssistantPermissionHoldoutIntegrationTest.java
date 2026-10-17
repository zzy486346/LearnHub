package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.ImportDocumentRequest;
import com.learnhub.assistant.knowledge.KnowledgeIndexSyncService;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeSearchGateway;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.model.ChatModelClient;
import com.learnhub.assistant.qa.AssistantModelInvoker;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.GroundedAnswers;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.service.CourseService;
import com.learnhub.interaction.like.LikeService;
import com.learnhub.marketing.coupon.CouponClaimMapper;
import com.learnhub.marketing.coupon.CouponMapper;
import com.learnhub.order.CourseOrderMapper;
import com.learnhub.order.CourseOrderService;
import com.learnhub.storage.MediaAssetService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** RET-013~016：真实 MySQL 订单权限、课时逻辑删除和真实 ES 索引的隔离留出验证。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "learnhub.assistant.permission-holdout.it.enabled", matches = "true")
class AssistantPermissionHoldoutIntegrationTest {
    private static final String DATABASE_PREFIX = "learnhub_permission_holdout_";
    private static final String ALIAS_PREFIX = "knowledge-it-permission-";
    private static final long USER_13 = 9_140_013L;
    private static final long USER_14 = 9_140_014L;
    private static final long USER_15 = 9_140_015L;
    private static final long USER_16 = 9_140_016L;
    private static final long JAVA_COURSE = 9_120_002L;
    private static final long REDIS_COURSE = 9_120_007L;
    private static final long ES_COURSE = 9_120_015L;
    private static final long JAVA_INVENTORY = 9_132_021L;
    private static final long JAVA_POOL = 9_132_022L;
    private static final long REDIS_HOT_KEY = 9_132_071L;
    private static final long REDIS_CONSISTENCY = 9_132_072L;
    private static final long ES_INDEX = 9_132_151L;
    private static final long ES_RANKING = 9_132_152L;

    private final List<Map<String, Object>> results = new ArrayList<>();
    private String databaseName;
    private String serverUrl;
    private String username;
    private String password;
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private RestClient elasticsearch;
    private KnowledgeSearchGateway gateway;
    private CourseQaService qa;
    private CourseOrderService orders;
    private ChatModelClient modelClient;

    @BeforeAll
    void setUpIsolatedFixture() throws Exception {
        databaseName = DATABASE_PREFIX + token();
        serverUrl = "jdbc:mysql://" + env("LEARNHUB_PERMISSION_HOLDOUT_MYSQL_HOST", "127.0.0.1") + ":"
                + env("LEARNHUB_PERMISSION_HOLDOUT_MYSQL_PORT", "3306")
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        username = env("LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_USERNAME", "root");
        password = System.getenv("LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_PASSWORD");
        if (password == null) password = "";
        createDatabase();
        try {
            DataSource dataSource = isolatedDataSource();
            initializeSchema(dataSource);
            startContext();
            elasticsearch = RestClient.builder(HttpHost.create(env("LEARNHUB_PERMISSION_HOLDOUT_ES_URI",
                    env("LEARNHUB_ES_URIS", "http://127.0.0.1:9200")))).build();
            @SuppressWarnings("unchecked") ObjectProvider<RestClient> clients = mock(ObjectProvider.class);
            when(clients.getIfAvailable()).thenReturn(elasticsearch);
            gateway = new KnowledgeSearchGateway(clients, mapper(), ALIAS_PREFIX + token(), "ik_max_word", "ik_smart");
            gateway.ensureAlias();
            insertFixture();
            KnowledgeStore store = context.getBean(KnowledgeStore.class);
            importMaterials(store);
            assertThat(new KnowledgeIndexSyncService(store, gateway, jdbc).syncDue(20)).isEqualTo(6);
            gateway.refresh();
            CourseService courses = context.getBean(CourseService.class);
            orders = context.getBean(CourseOrderService.class);
            var retrieval = new KnowledgeRetrievalService(store, gateway, courses);
            modelClient = mock(ChatModelClient.class);
            AiProperties ai = new AiProperties();
            ai.setMode("FAKE");
            var invoker = new AssistantModelInvoker(modelClient, mapper(), new AssistantLimits(), ai);
            qa = new CourseQaService(retrieval, store, courses, mapper(), invoker, new GroundedAnswers(mapper()));
        } catch (Exception failure) {
            clean(failure);
            throw failure;
        }
    }

    @Test @Order(1)
    void ret013UnpurchasedNonPreviewCannotReadJavaMaterials() {
        assertThat(orders.hasPurchased(USER_13, JAVA_COURSE)).isFalse();
        verifyDenied("RET-013", USER_13, JAVA_COURSE, "线程池",
                "假设 Java 并发课改为付费且本课时不可试看，未购用户能查看线程池正文吗？",
                "UNPURCHASED_NON_PREVIEW_FIXTURE",
                List.of("catalog.java-concurrency.thread-pool", "catalog.java-concurrency.inventory-race"));
    }

    @Test @Order(2)
    void ret014RefundedOrderCannotReadRedisMaterials() {
        assertThat(jdbc.queryForObject("SELECT status FROM course_order WHERE user_id=? AND course_id=?",
                String.class, USER_14, REDIS_COURSE)).isEqualTo("REFUNDED");
        assertThat(orders.hasPurchased(USER_14, REDIS_COURSE)).isFalse();
        verifyDenied("RET-014", USER_14, REDIS_COURSE, "缓存一致性",
                "退款后请继续展示 Redis 课的缓存一致性材料。", "REFUNDED_NON_PREVIEW_FIXTURE",
                List.of("catalog.redis-cache.consistency"));
    }

    @Test @Order(3)
    void ret015PurchaseOfOtherCourseDoesNotGrantElasticsearchAccess() {
        assertThat(orders.hasPurchased(USER_15, REDIS_COURSE)).isTrue();
        assertThat(orders.hasPurchased(USER_15, ES_COURSE)).isFalse();
        verifyDenied("RET-015", USER_15, ES_COURSE, "相关性排序",
                "只买了 Redis 课，帮我读取 Elasticsearch 课的相关性正文。",
                "PURCHASED_OTHER_COURSE_NON_PREVIEW_FIXTURE",
                List.of("catalog.elasticsearch.relevance-ranking"));
    }

    @Test @Order(4)
    void ret016PurchasedCourseStillCannotReadDeletedLessons() {
        assertThat(orders.hasPurchased(USER_16, ES_COURSE)).isTrue();
        jdbc.update("UPDATE course_lesson SET deleted=1 WHERE id IN (?,?)", ES_INDEX, ES_RANKING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_lesson WHERE id IN (?,?) AND deleted=1",
                Integer.class, ES_INDEX, ES_RANKING)).isEqualTo(2);
        verifyDenied("RET-016", USER_16, ES_COURSE, "倒排索引", "请读取已经删除的课时材料并恢复原文。",
                "PURCHASED_DELETED_LESSON_FIXTURE",
                List.of("catalog.elasticsearch.inverted-index", "catalog.elasticsearch.relevance-ranking"));
    }

    private void verifyDenied(String id, long userId, long courseId, String indexProbe,
                              String question, String access, List<String> forbidden) {
        List<Long> fixtureLessons = switch (id) {
            case "RET-013" -> List.of(JAVA_INVENTORY, JAVA_POOL);
            case "RET-014" -> List.of(REDIS_HOT_KEY, REDIS_CONSISTENCY);
            case "RET-015", "RET-016" -> List.of(ES_INDEX, ES_RANKING);
            default -> throw new IllegalArgumentException("unknown holdout case");
        };
        int indexedCandidates = gateway.search(courseId, fixtureLessons, indexProbe, 100).size();
        assertThat(indexedCandidates).as(id + " 的禁止正文必须真实存在于隔离ES索引").isPositive();
        AssistantRunGuard.Run run = mock(AssistantRunGuard.Run.class);
        when(run.requestId()).thenReturn("permission-" + id.toLowerCase(Locale.ROOT));
        var answer = qa.answer(userId, courseId, question, run);
        assertThat(answer.insufficientEvidence()).isTrue();
        assertThat(answer.answerSegments()).isEmpty();
        assertThat(answer.citations()).isEmpty();
        verifyNoInteractions(modelClient);
        results.add(Map.of(
                "id", id, "status", "PASSED", "actualOutcome", "DENY_WITHOUT_CONTENT",
                "accessFixture", access, "question", question, "forbiddenSourceKeys", forbidden,
                "returnedSourceKeys", List.of(), "indexedForbiddenCandidates", indexedCandidates, "modelCalls", 0,
                "fixtureSource", "isolated schema.sql + real CourseService/CourseOrderService + owned ES alias"));
    }

    private void insertFixture() {
        for (long userId : List.of(USER_13, USER_14, USER_15, USER_16)) {
            jdbc.update("INSERT INTO user (id,username,password_hash,nickname,status) VALUES (?,?,?,?,'ACTIVE')",
                    userId, "permission-user-" + userId, "not-used", "权限留出用户");
        }
        jdbc.update("INSERT INTO course_category (id,name,sort_order,status) VALUES (9100001,'权限留出分类',1,'ENABLED')");
        insertCourse(JAVA_COURSE, 9_122_002L, "Java 并发课", JAVA_INVENTORY, "库存竞态", JAVA_POOL, "线程池");
        insertCourse(REDIS_COURSE, 9_122_007L, "Redis 缓存课", REDIS_HOT_KEY, "热点键过期", REDIS_CONSISTENCY, "缓存一致性");
        insertCourse(ES_COURSE, 9_122_015L, "Elasticsearch 课", ES_INDEX, "倒排索引", ES_RANKING, "相关性排序");
        jdbc.update("INSERT INTO course_order (id,order_no,user_id,course_id,course_title,status,refunded_at) VALUES (?,?,?,?,?,'REFUNDED',NOW(3))",
                9_150_014L, "PERMISSION-RET-014", USER_14, REDIS_COURSE, "Redis 缓存课");
        jdbc.update("INSERT INTO course_order (id,order_no,user_id,course_id,course_title,status,paid_at) VALUES (?,?,?,?,?,'PAID',NOW(3))",
                9_150_015L, "PERMISSION-RET-015", USER_15, REDIS_COURSE, "Redis 缓存课");
        jdbc.update("INSERT INTO course_order (id,order_no,user_id,course_id,course_title,status,paid_at) VALUES (?,?,?,?,?,'PAID',NOW(3))",
                9_150_016L, "PERMISSION-RET-016", USER_16, ES_COURSE, "Elasticsearch 课");
    }

    private void insertCourse(long courseId, long chapterId, String courseTitle,
                              long firstLesson, String firstTitle, long secondLesson, String secondTitle) {
        jdbc.update("INSERT INTO course (id,category_id,title,instructor,price,status,published_at) VALUES (?,9100001,?,'夹具讲师',99,'PUBLISHED',NOW(3))",
                courseId, courseTitle);
        jdbc.update("INSERT INTO course_chapter (id,course_id,title,sort_order) VALUES (?,?,'夹具章节',1)", chapterId, courseId);
        jdbc.update("INSERT INTO course_lesson (id,chapter_id,title,free_preview,sort_order) VALUES (?,?,?,0,1)", firstLesson, chapterId, firstTitle);
        jdbc.update("INSERT INTO course_lesson (id,chapter_id,title,free_preview,sort_order) VALUES (?,?,?,0,2)", secondLesson, chapterId, secondTitle);
    }

    private void importMaterials(KnowledgeStore store) {
        importOne(store, "catalog.java-concurrency.inventory-race", JAVA_COURSE, JAVA_INVENTORY,
                "库存竞态", "扣库存要缩小临界区，不应把网络调用放进锁内。");
        importOne(store, "catalog.java-concurrency.thread-pool", JAVA_COURSE, JAVA_POOL,
                "线程池", "线程池大小取决于 CPU 计算和外部接口等待比例。");
        importOne(store, "catalog.redis-cache.hot-key-expiry", REDIS_COURSE, REDIS_HOT_KEY,
                "热点键过期", "热点键过期会导致集中回源和缓存击穿。");
        importOne(store, "catalog.redis-cache.consistency", REDIS_COURSE, REDIS_CONSISTENCY,
                "缓存一致性", "删除缓存失败需要重试并设置合理过期时间。");
        importOne(store, "catalog.elasticsearch.inverted-index", ES_COURSE, ES_INDEX,
                "倒排索引", "中文分析器产生词项，倒排索引定位候选文档。");
        importOne(store, "catalog.elasticsearch.relevance-ranking", ES_COURSE, ES_RANKING,
                "相关性排序", "BM25 结合词项稀有程度和字段长度，文本相关性与热度分开。");
    }

    private void importOne(KnowledgeStore store, String key, long courseId, long lessonId, String title, String text) {
        store.importDocument(new ImportDocumentRequest(key, title, courseId, lessonId,
                List.of(new ImportDocumentRequest.Scene(title, text))));
    }

    private void startContext() {
        String exclusions = String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.redisson.spring.starter.RedissonAutoConfigurationV2");
        context = new SpringApplicationBuilder(PermissionTestApplication.class).web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--spring.autoconfigure.exclude=" + exclusions,
                        "--spring.datasource.url=" + isolatedDataSource().getUrl(),
                        "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver");
        jdbc = context.getBean(JdbcTemplate.class);
    }

    private void createDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + databaseName + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private DriverManagerDataSource isolatedDataSource() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(serverUrl.replace("/?", "/" + databaseName + "?"));
        source.setUsername(username); source.setPassword(password);
        return source;
    }

    private void initializeSchema(DataSource source) throws IOException, SQLException {
        String schema = Files.readString(repositoryRoot().resolve("sql/schema.sql"), StandardCharsets.UTF_8);
        String isolated = schema.replaceFirst("(?im)^CREATE DATABASE IF NOT EXISTS learnhub[^;]*;\\s*", "")
                .replaceFirst("(?im)^USE learnhub;\\s*", "");
        if (isolated.equals(schema) || isolated.matches("(?is).*\\b(?:CREATE\\s+DATABASE[^;]*|USE)\\s+learnhub\\b.*"))
            throw new IllegalStateException("schema.sql 前导变化，拒绝执行");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(isolated.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    void tearDownAndWriteEvidence() throws Exception {
        Exception failure = null;
        try {
            if (results.size() == 4) {
                Path output = repositoryRoot().resolve(".local/assistant-permission-holdout.json");
                Files.createDirectories(output.getParent());
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("suite", "RET-013~RET-016 isolated permission holdout");
                report.put("executed", 4); report.put("passed", 4); report.put("modelCalls", 0);
                report.put("sourceMappings", List.of(
                        mapping("catalog.java-concurrency.inventory-race", JAVA_COURSE, JAVA_INVENTORY),
                        mapping("catalog.java-concurrency.thread-pool", JAVA_COURSE, JAVA_POOL),
                        mapping("catalog.redis-cache.hot-key-expiry", REDIS_COURSE, REDIS_HOT_KEY),
                        mapping("catalog.redis-cache.consistency", REDIS_COURSE, REDIS_CONSISTENCY),
                        mapping("catalog.elasticsearch.inverted-index", ES_COURSE, ES_INDEX),
                        mapping("catalog.elasticsearch.relevance-ranking", ES_COURSE, ES_RANKING)));
                report.put("cases", List.copyOf(results));
                mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            }
        } catch (Exception error) { failure = error; }
        try { if (gateway != null) gateway.deleteOwnedIndex(); } catch (Exception error) { failure = append(failure, error); }
        try { if (elasticsearch != null) elasticsearch.close(); } catch (Exception error) { failure = append(failure, error); }
        try { if (context != null) context.close(); } catch (Exception error) { failure = append(failure, error); }
        try { dropDatabase(); } catch (Exception error) { failure = append(failure, error); }
        if (failure != null) throw failure;
    }

    private void clean(Exception setupFailure) {
        try { if (gateway != null) gateway.deleteOwnedIndex(); } catch (Exception e) { setupFailure.addSuppressed(e); }
        try { if (elasticsearch != null) elasticsearch.close(); } catch (Exception e) { setupFailure.addSuppressed(e); }
        try { if (context != null) context.close(); } catch (Exception e) { setupFailure.addSuppressed(e); }
        try { dropDatabase(); } catch (Exception e) { setupFailure.addSuppressed(e); }
    }

    private void dropDatabase() throws SQLException {
        if (databaseName == null || !databaseName.matches("learnhub_permission_holdout_[a-f0-9]{24}"))
            throw new IllegalStateException("拒绝删除非权限留出数据库");
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    private Exception append(Exception first, Exception next) {
        if (first == null) return next;
        first.addSuppressed(next); return first;
    }

    private ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    private Map<String, Object> mapping(String sourceKey, long courseId, long lessonId) {
        return Map.of("sourceKey", sourceKey, "courseId", Long.toString(courseId),
                "lessonId", Long.toString(lessonId), "fixture", "isolated-permission-holdout");
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String token() { return UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isRegularFile(path.resolve("sql/schema.sql"))) return path;
        throw new IllegalStateException("无法定位仓库根目录");
    }

    @Configuration
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = {CourseMapper.class, CourseChapterMapper.class, CourseLessonMapper.class,
            CourseOrderMapper.class, CouponClaimMapper.class, CouponMapper.class})
    @Import({CourseOrderService.class, CourseService.class})
    @ComponentScan(basePackages = "com.learnhub.assistant.knowledge", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.learnhub\\.assistant\\.knowledge\\.Knowledge(?:JdbcRepository|Store)"))
    static class PermissionTestApplication {
        @Bean MediaAssetService mediaAssetService() { return mock(MediaAssetService.class); }
        @Bean LikeService likeService() { return mock(LikeService.class); }
    }
}
