package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.config.InfrastructureConfig;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.ProgressUpdateRequest;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.AdminCourseLockMapper;
import com.learnhub.course.mapper.CourseCategoryMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.mapper.LearningProgressMapper;
import com.learnhub.course.mapper.CourseTagBindingMapper;
import com.learnhub.course.mapper.CourseTagMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.interaction.like.LikeService;
import com.learnhub.marketing.coupon.CouponClaimMapper;
import com.learnhub.order.CourseOrderService;
import com.learnhub.order.CourseOrderMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.learnhub.search.CourseIndexTaskService;
import com.learnhub.storage.MediaAssetService;
import com.learnhub.storage.MediaAssetMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "learnhub.admin.course.it.enabled", matches = "true")
class AdminCourseInfrastructureIntegrationTest {
    private static final long ENABLED_CATEGORY_ID = 91001L;
    private final List<Long> lessonIds = new ArrayList<>();
    private final List<Long> progressIds = new ArrayList<>();
    private final List<Long> chapterIds = new ArrayList<>();
    private final List<Long> courseIds = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private ConfigurableApplicationContext context;

    @BeforeAll
    void startIsolatedContext() {
        String exclusions = String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.redisson.spring.starter.RedissonAutoConfigurationV2");
        context = new SpringApplicationBuilder(AdminCourseTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.autoconfigure.exclude=" + exclusions,
                        "--spring.datasource.url=" + mysqlUrl(),
                        "--spring.datasource.username=" + property("learnhub.admin.course.it.mysql-username", "learnhub"),
                        "--spring.datasource.password=" + property("learnhub.admin.course.it.mysql-password", "learnhub"),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.datasource.hikari.maximum-pool-size=2",
                        "--learnhub.rabbit.enabled=false",
                        "--learnhub.storage.oss.enabled=false");
        var deletedColumn = jdbc().queryForMap("""
                SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_lesson' AND COLUMN_NAME = 'deleted'
                """);
        assertThat(deletedColumn.get("COLUMN_TYPE").toString()).isEqualTo("tinyint");
        assertThat(deletedColumn.get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(deletedColumn.get("COLUMN_DEFAULT").toString()).isEqualTo("0");
        assertThat(deletedColumn.get("COLUMN_COMMENT")).isEqualTo("逻辑删除标记：0正常，1已删除");
    }

    @AfterEach
    void cleanOwnedRows() {
        JdbcTemplate jdbc = jdbc();
        RuntimeException cleanupFailure = null;
        cleanupFailure = deleteOwnedRows(jdbc, "learning_progress", progressIds, cleanupFailure);
        cleanupFailure = deleteOwnedRows(jdbc, "course_order", orderIds, cleanupFailure);
        cleanupFailure = deleteOwnedRows(jdbc, "course_lesson", lessonIds, cleanupFailure);
        cleanupFailure = deleteOwnedRows(jdbc, "course_chapter", chapterIds, cleanupFailure);
        cleanupFailure = deleteOwnedRows(jdbc, "course", courseIds, cleanupFailure);
        if (cleanupFailure != null) throw cleanupFailure;
    }

    private RuntimeException deleteOwnedRows(JdbcTemplate jdbc, String tableName, List<Long> ids,
            RuntimeException cleanupFailure) {
        for (Long id : List.copyOf(ids)) {
            try {
                jdbc.update("DELETE FROM " + tableName + " WHERE id = ?", id);
                ids.remove(id);
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) cleanupFailure = new IllegalStateException("隔离测试数据清理失败");
                cleanupFailure.addSuppressed(exception);
            }
        }
        return cleanupFailure;
    }

    @AfterAll
    void stopIsolatedContext() {
        try {
            if (context != null) cleanOwnedRows();
        } finally {
            if (context != null) context.close();
        }
    }

    @Test
    void databasePaginationUsesFixedTenRowsAndDescendingIds() {
        long firstId = uniqueBase();
        for (int i = 0; i < 12; i++) insertCourse(firstId + i, "分页课程" + i, "DRAFT");

        Page<Course> firstPage = adminService().list(1);
        Page<Course> secondPage = adminService().list(2);

        assertThat(firstPage.getSize()).isEqualTo(10);
        assertThat(firstPage.getTotal()).isEqualTo(12);
        assertThat(firstPage.getRecords()).hasSize(10);
        assertThat(firstPage.getRecords()).extracting(Course::getId)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(firstId + 2, firstId + 11)
                        .boxed().sorted(java.util.Comparator.reverseOrder()).toList());
        assertThat(secondPage.getRecords()).extracting(Course::getId)
                .containsExactly(firstId + 1, firstId);
    }

    @Test
    void logicalDeleteRemovesLessonFromDetailsAndAccessButPreservesRow() {
        long courseId = uniqueBase();
        long chapterId = courseId + 1;
        insertCourse(courseId, "逻辑删除课程", "DRAFT");
        insertChapter(chapterId, courseId);
        Long lessonId = adminService().createLesson(chapterId,
                new AdminCourseRequests.CreateLesson("  第一课  ", 60, true, 1));
        lessonIds.add(lessonId);
        long userId = uniqueBase();
        long progressId = uniqueBase();
        progressIds.add(progressId);
        jdbc().update("""
                INSERT INTO learning_progress (id, user_id, lesson_id, position_seconds, completed, last_learned_at)
                VALUES (?, ?, ?, 60, 1, CURRENT_TIMESTAMP(3))
                """, progressId, userId, lessonId);

        assertThat(adminService().detail(courseId).chapters().get(0).lessons()).hasSize(1);
        assertThat(context.getBean(LearningProgressMapper.class).countCompletedLessons(userId)).isEqualTo(1);

        adminService().deleteLesson(lessonId);

        assertThat(jdbc().queryForObject(
                "SELECT deleted FROM course_lesson WHERE id = ?", Integer.class, lessonId)).isEqualTo(1);
        assertThat(adminService().detail(courseId).chapters().get(0).lessons()).isEmpty();
        assertThat(context.getBean(LearningProgressMapper.class).countLessonInCourse(lessonId, courseId)).isZero();
        assertThat(context.getBean(LearningProgressMapper.class).countCompletedLessons(userId)).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM learning_progress WHERE id = ?", Long.class, progressId)).isEqualTo(1);
        assertThatThrownBy(() -> adminService().deleteLesson(lessonId))
                .isInstanceOf(BusinessException.class).hasMessage("课时不存在");
    }

    @Test
    void lessonCreationRejectsMissingCourseAndTrimmedBlankTitle() {
        long missingCourseChapterId = uniqueBase();
        insertChapter(missingCourseChapterId, missingCourseChapterId + 1);
        assertThatThrownBy(() -> adminService().createLesson(missingCourseChapterId,
                new AdminCourseRequests.CreateLesson("第一课", 60, false, 1)))
                .isInstanceOf(BusinessException.class).hasMessage("课程不存在");

        long courseId = uniqueBase();
        long chapterId = courseId + 1;
        insertCourse(courseId, "创建校验课程", "DRAFT");
        insertChapter(chapterId, courseId);
        assertThatThrownBy(() -> adminService().createLesson(chapterId,
                new AdminCourseRequests.CreateLesson("   ", 60, false, 1)))
                .isInstanceOf(BusinessException.class).hasMessage("课时标题不能为空");
    }

    private void insertCourse(long id, String title, String status) {
        courseIds.add(id);
        jdbc().update("""
                INSERT INTO course (id, category_id, title, instructor, price, status, like_count)
                VALUES (?, ?, ?, 'integration-test', ?, ?, 0)
                """, id, ENABLED_CATEGORY_ID, title, BigDecimal.ZERO, status);
    }

    @Test
    void concurrentFirstSavesAreAtomicAndCompletedNeverRegresses() throws Exception {
        long courseId = uniqueBase();
        long chapterId = courseId + 1;
        long userId = uniqueBase();
        insertCourse(courseId, "并发进度课程", "DRAFT");
        insertChapter(chapterId, courseId);
        long lessonId = adminService().createLesson(chapterId,
                new AdminCourseRequests.CreateLesson("并发课时", 60, true, 1));
        lessonIds.add(lessonId);
        setLegacyMedia(lessonId);
        adminService().publishCourse(courseId);
        var progress = context.getBean(LearningProgressService.class);
        var executor = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                    CompletableFuture.runAsync(() -> {
                        try { gate.await(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); throw new RuntimeException(e);
                        }
                        progress.save(userId, courseId, new ProgressUpdateRequest(lessonId, i * 20, i == 3));
                    }, executor)).toList();
            gate.countDown();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            progress.save(userId, courseId, new ProgressUpdateRequest(lessonId, 999, false));
            var saved = progress.getByCourse(userId, courseId);
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getCompleted()).isTrue();
            assertThat(saved.get(0).getPositionSeconds()).isEqualTo(60);
            LearningProgress olderRequest = new LearningProgress();
            olderRequest.setId(uniqueBase());
            olderRequest.setUserId(userId);
            olderRequest.setLessonId(lessonId);
            olderRequest.setPositionSeconds(1);
            olderRequest.setCompleted(false);
            olderRequest.setLastLearnedAt(saved.get(0).getLastLearnedAt().minusMinutes(1));
            context.getBean(LearningProgressMapper.class).upsert(olderRequest);
            var afterLateRequest = progress.getByCourse(userId, courseId).get(0);
            assertThat(afterLateRequest.getPositionSeconds()).isEqualTo(60);
            assertThat(afterLateRequest.getCompleted()).isTrue();
            assertThat(afterLateRequest.getLastLearnedAt()).isEqualTo(saved.get(0).getLastLearnedAt());
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("进度测试线程未退出");
            progressIds.addAll(jdbc().queryForList(
                    "SELECT id FROM learning_progress WHERE user_id = ? AND lesson_id = ?", Long.class, userId, lessonId));
        }
    }

    @Test
    void offlineCoursePreservesPaidAccessAndRejectsPendingPaymentUntilRepublished() {
        long courseId = uniqueBase();
        long chapterId = courseId + 1;
        long paidUser = uniqueBase();
        long pendingUser = uniqueBase();
        insertCourse(courseId, "下架权益课程", "DRAFT");
        insertChapter(chapterId, courseId);
        long lessonId = adminService().createLesson(chapterId,
                new AdminCourseRequests.CreateLesson("已购课时", 60, false, 1));
        lessonIds.add(lessonId);
        setLegacyMedia(lessonId);
        adminService().publishCourse(courseId);
        var orders = context.getBean(CourseOrderService.class);
        long paidOrderId = Long.parseLong(orders.create(paidUser, courseId).id());
        orderIds.add(paidOrderId);
        orders.pay(paidUser, paidOrderId);
        long pendingId = Long.parseLong(orders.create(pendingUser, courseId).id());
        orderIds.add(pendingId);
        adminService().offlineCourse(courseId);
        assertThat(courseService().detail(courseId, paidUser).accessLevel()).isEqualTo("FULL");
        var progress = context.getBean(LearningProgressService.class);
        var saved = progress.save(paidUser, courseId, new ProgressUpdateRequest(lessonId, 60, true));
        progressIds.add(saved.getId());
        assertThat(progress.getByCourse(paidUser, courseId)).hasSize(1);
        assertThatThrownBy(() -> courseService().detail(courseId, pendingUser)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.pay(pendingUser, pendingId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.create(uniqueBase(), courseId)).isInstanceOf(BusinessException.class);
        assertThat(jdbc().queryForObject("SELECT status FROM course_order WHERE id = ?", String.class, pendingId))
                .isEqualTo("PENDING");
        adminService().publishCourse(courseId);
        assertThat(orders.pay(pendingUser, pendingId).status()).isEqualTo("PAID");
    }

    @Test
    void rejectsPublishingIncompleteCourseAndPreservesLastLessonOfPublishedCourse() {
        long courseId = uniqueBase();
        long chapterId = courseId + 1;
        insertCourse(courseId, "发布完整性课程", "DRAFT");
        assertThatThrownBy(() -> adminService().publishCourse(courseId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_NOT_READY"));
        insertChapter(chapterId, courseId);
        long lessonId = adminService().createLesson(chapterId,
                new AdminCourseRequests.CreateLesson("完整课时", 60, false, 1));
        lessonIds.add(lessonId);
        assertThatThrownBy(() -> adminService().publishCourse(courseId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_NOT_READY"));
        setLegacyMedia(lessonId);
        long emptyChapterId = chapterId + 1;
        insertChapter(emptyChapterId, courseId);
        assertThatThrownBy(() -> adminService().publishCourse(courseId))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("COURSE_NOT_READY");
                    assertThat(exception.getMessage()).contains("集成测试章节");
                });
        long secondLessonId = adminService().createLesson(emptyChapterId,
                new AdminCourseRequests.CreateLesson("第二章课时", 60, false, 1));
        lessonIds.add(secondLessonId);
        setLegacyMedia(secondLessonId);
        adminService().publishCourse(courseId);

        assertThatThrownBy(() -> adminService().createChapter(courseId,
                new AdminCourseRequests.CreateChapter("发布后追加章节", 3)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_OFFLINE_REQUIRED"));
        assertThatThrownBy(() -> adminService().deleteLesson(lessonId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_OFFLINE_REQUIRED"));
        assertThat(jdbc().queryForObject("SELECT deleted FROM course_lesson WHERE id = ?", Integer.class, lessonId))
                .isZero();
    }

    private void insertChapter(long id, long courseId) {
        chapterIds.add(id);
        jdbc().update("INSERT INTO course_chapter (id, course_id, title, sort_order) VALUES (?, ?, ?, 1)",
                id, courseId, "集成测试章节");
    }

    private void setLegacyMedia(long lessonId) {
        jdbc().update("UPDATE course_lesson SET media_url = ? WHERE id = ?",
                "https://media.example/" + lessonId + ".mp4", lessonId);
    }

    private long uniqueBase() {
        return 8_000_000_000_000_000L + (UUID.randomUUID().getMostSignificantBits() & 0x0000FFFFFFFFFFFFL);
    }

    private String mysqlUrl() {
        String explicit = System.getProperty("learnhub.admin.course.it.mysql-url");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String host = property("learnhub.admin.course.it.mysql-host", "localhost");
        String port = property("learnhub.admin.course.it.mysql-port", "13306");
        String database = property("learnhub.admin.course.it.mysql-database", "learnhub_admin_course_it");
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    private String property(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }

    private JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    private AdminCourseService adminService() {
        return context.getBean(AdminCourseService.class);
    }

    private CourseService courseService() {
        return context.getBean(CourseService.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = {
            CourseMapper.class, AdminCourseLockMapper.class, CourseCategoryMapper.class,
            CourseTagMapper.class, CourseTagBindingMapper.class, CourseChapterMapper.class,
            CourseLessonMapper.class, LearningProgressMapper.class, CourseOrderMapper.class,
            CouponClaimMapper.class, MediaAssetMapper.class
    })
    @Import({InfrastructureConfig.class, AdminCourseService.class, CourseService.class,
            CourseTaxonomyService.class, CourseOrderService.class, LearningProgressService.class})
    static class AdminCourseTestApplication {
        @Bean MediaAssetService mediaAssetService() { return mock(MediaAssetService.class); }
        @Bean LikeService likeService() { return mock(LikeService.class); }
        @Bean CourseIndexTaskService courseIndexTaskService() { return mock(CourseIndexTaskService.class); }
    }
}
