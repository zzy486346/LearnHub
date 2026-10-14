package com.learnhub.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.marketing.coupon.CouponClaimMapper;
import com.learnhub.marketing.coupon.CouponMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "learnhub.commerce.it.enabled", matches = "true")
class CommerceInfrastructureIntegrationTest {
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> claimIds = new ArrayList<>();
    private final List<Long> couponIds = new ArrayList<>();
    private final List<Long> courseIds = new ArrayList<>();
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
        context = new SpringApplicationBuilder(CommerceTestApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .run("--spring.main.banner-mode=off", "--spring.autoconfigure.exclude=" + exclusions,
                        "--spring.datasource.url=" + mysqlUrl(),
                        "--spring.datasource.username=" + property("learnhub.commerce.it.mysql-username", "learnhub"),
                        "--spring.datasource.password=" + property("learnhub.commerce.it.mysql-password", "learnhub"),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.datasource.hikari.maximum-pool-size=6",
                        "--learnhub.payment.mode=LOCAL_DEMO",
                        "--learnhub.payment.pending-timeout=PT30M");
        assertThat(jdbc().queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order'
                  AND COLUMN_NAME IN ('coupon_claim_id','discount_amount','payable_amount','payment_mode',
                                      'expires_at','cancelled_at','refunded_at')
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc().queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim'
                  AND COLUMN_NAME IN ('reserved_order_id','used_order_id','reserved_at','used_at')
                """, Integer.class)).isEqualTo(4);
    }

    @AfterEach
    void cleanup() {
        deleteRows("course_order", orderIds);
        deleteRows("coupon_claim", claimIds);
        deleteRows("coupon", couponIds);
        deleteRows("course", courseIds);
    }

    @AfterAll
    void stopContext() {
        if (context != null) context.close();
    }

    @Test
    void couponOrderCanPayRefundRepurchaseAndCancel() {
        Fixture fixture = fixture();
        CourseOrderService service = service();

        CourseOrderResponse pending = service.create(fixture.userId(), fixture.courseId(), fixture.claimId());
        orderIds.add(Long.valueOf(pending.id()));
        assertThat(pending.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(pending.payableAmount()).isEqualByComparingTo("70.00");
        assertClaim(fixture.claimId(), "RESERVED", Long.valueOf(pending.id()), null);

        CourseOrderResponse paid = service.pay(fixture.userId(), Long.valueOf(pending.id()));
        assertThat(paid.status()).isEqualTo(CourseOrderService.PAID);
        assertThat(paid.payableAmount()).isEqualByComparingTo("70.00");
        assertThat(paid.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(service.hasPurchased(fixture.userId(), fixture.courseId())).isTrue();
        assertClaim(fixture.claimId(), "USED", null, Long.valueOf(pending.id()));

        CourseOrderResponse refunded = service.refund(fixture.userId(), Long.valueOf(pending.id()));
        assertThat(refunded.status()).isEqualTo(CourseOrderService.REFUNDED);
        assertThat(service.hasPurchased(fixture.userId(), fixture.courseId())).isFalse();
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);

        CourseOrderResponse repurchased = service.create(fixture.userId(), fixture.courseId());
        assertThat(repurchased.id()).isEqualTo(pending.id());
        assertThat(repurchased.orderNo()).isNotEqualTo(pending.orderNo());
        assertThat(repurchased.couponClaimId()).isNull();
        assertThat(repurchased.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(repurchased.payableAmount()).isEqualByComparingTo("100.00");
        assertReusedOrderNullableFieldsCleared(Long.valueOf(repurchased.id()));

        CourseOrderResponse repaid = service.pay(fixture.userId(), Long.valueOf(repurchased.id()));
        assertThat(repaid.status()).isEqualTo(CourseOrderService.PAID);
        assertThat(repaid.couponClaimId()).isNull();
        CourseOrderResponse secondRefund = service.refund(fixture.userId(), Long.valueOf(repurchased.id()));
        assertThat(secondRefund.status()).isEqualTo(CourseOrderService.REFUNDED);

        CourseOrderResponse reservedAgain = service.create(fixture.userId(), fixture.courseId(), fixture.claimId());
        assertThat(reservedAgain.couponClaimId()).isEqualTo(fixture.claimId());
        CourseOrderResponse changedToNoCoupon = service.create(fixture.userId(), fixture.courseId());
        assertThat(changedToNoCoupon.couponClaimId()).isNull();
        assertThat(changedToNoCoupon.payableAmount()).isEqualByComparingTo("100.00");
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
        assertReusedOrderNullableFieldsCleared(Long.valueOf(changedToNoCoupon.id()));
        CourseOrderResponse cancelled = service.cancel(fixture.userId(), Long.valueOf(changedToNoCoupon.id()));
        assertThat(cancelled.status()).isEqualTo(CourseOrderService.CANCELLED);
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
    }

    @Test
    void concurrentPayAndCancelLeaveOneConsistentTerminalState() throws Exception {
        Fixture fixture = fixture();
        CourseOrderResponse pending = service().create(fixture.userId(), fixture.courseId(), fixture.claimId());
        long orderId = Long.parseLong(pending.id());
        orderIds.add(orderId);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> pay = CompletableFuture.runAsync(() -> runAfter(start,
                    () -> service().pay(fixture.userId(), orderId)), executor);
            CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> runAfter(start,
                    () -> service().cancel(fixture.userId(), orderId)), executor);
            start.countDown();
            CompletableFuture.allOf(pay, cancel).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        String status = jdbc().queryForObject("SELECT status FROM course_order WHERE id = ?", String.class, orderId);
        String claimStatus = jdbc().queryForObject("SELECT status FROM coupon_claim WHERE id = ?", String.class, fixture.claimId());
        assertThat(status).isIn(CourseOrderService.PAID, CourseOrderService.CANCELLED);
        assertThat(claimStatus).isEqualTo(CourseOrderService.PAID.equals(status) ? "USED" : "AVAILABLE");
    }

    @Test
    void refundRollsBackWhenCouponUseStateHasDrifted() {
        Fixture fixture = fixture();
        CourseOrderResponse pending = service().create(fixture.userId(), fixture.courseId(), fixture.claimId());
        long orderId = Long.parseLong(pending.id());
        orderIds.add(orderId);
        service().pay(fixture.userId(), orderId);
        jdbc().update("""
                UPDATE coupon_claim
                SET status = 'AVAILABLE', used_order_id = NULL, used_at = NULL
                WHERE id = ?
                """, fixture.claimId());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service().refund(fixture.userId(), orderId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COUPON_USE_INVALID"));

        assertThat(jdbc().queryForObject("SELECT status FROM course_order WHERE id = ?", String.class, orderId))
                .isEqualTo(CourseOrderService.PAID);
    }

    @Test
    void scheduledClosureReturnsCouponFromExpiredOrder() {
        Fixture fixture = fixture();
        CourseOrderResponse pending = service().create(fixture.userId(), fixture.courseId(), fixture.claimId());
        long orderId = Long.parseLong(pending.id());
        orderIds.add(orderId);
        jdbc().update("UPDATE course_order SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", orderId);

        service().closeExpiredOrders();

        assertThat(jdbc().queryForObject("SELECT status FROM course_order WHERE id = ?", String.class, orderId))
                .isEqualTo(CourseOrderService.CLOSED);
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
    }

    @Test
    void concurrentTwoArgumentCreateUsesTransactionalProxyAndKeepsSingleOrder() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        List<CourseOrderResponse> responses;
        var executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CourseOrderResponse> first = CompletableFuture.supplyAsync(
                    () -> createAfter(start, fixture), executor);
            CompletableFuture<CourseOrderResponse> second = CompletableFuture.supplyAsync(
                    () -> createAfter(start, fixture), executor);
            start.countDown();
            responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        long orderId = Long.parseLong(responses.get(0).id());
        orderIds.add(orderId);
        assertThat(responses).extracting(CourseOrderResponse::id).containsOnly(responses.get(0).id());
        assertThat(jdbc().queryForObject("""
                SELECT COUNT(*) FROM course_order WHERE user_id = ? AND course_id = ?
                """, Integer.class, fixture.userId(), fixture.courseId())).isEqualTo(1);
    }

    @Test
    void oneCouponCannotBeReservedByTwoCoursesConcurrently() throws Exception {
        Fixture fixture = fixture();
        long secondCourseId = fixture.courseId() + 2_000_000L;
        courseIds.add(secondCourseId);
        insertCourse(secondCourseId, "并发验收课程二");
        CountDownLatch start = new CountDownLatch(1);
        List<CreateAttempt> attempts;
        var executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CreateAttempt> first = CompletableFuture.supplyAsync(
                    () -> createWithCouponAfter(start, fixture.userId(), fixture.courseId(), fixture.claimId()), executor);
            CompletableFuture<CreateAttempt> second = CompletableFuture.supplyAsync(
                    () -> createWithCouponAfter(start, fixture.userId(), secondCourseId, fixture.claimId()), executor);
            start.countDown();
            attempts = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(attempts).filteredOn(attempt -> attempt.response() != null).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> "COUPON_NOT_AVAILABLE".equals(attempt.errorCode())).hasSize(1);
        long winningOrderId = jdbc().queryForObject("""
                SELECT id FROM course_order WHERE user_id = ? AND course_id IN (?, ?)
                """, Long.class, fixture.userId(), fixture.courseId(), secondCourseId);
        orderIds.add(winningOrderId);
        assertThat(jdbc().queryForObject("""
                SELECT COUNT(*) FROM course_order WHERE user_id = ? AND course_id IN (?, ?)
                """, Integer.class, fixture.userId(), fixture.courseId(), secondCourseId)).isEqualTo(1);
        assertClaim(fixture.claimId(), "RESERVED", winningOrderId, null);
    }

    @Test
    void paymentRechecksOrderExpiryAfterWaitingForCouponLock() throws Exception {
        Fixture fixture = fixture();
        CourseOrderResponse pending = service().create(fixture.userId(), fixture.courseId(), fixture.claimId());
        long orderId = Long.parseLong(pending.id());
        orderIds.add(orderId);
        jdbc().update("UPDATE course_order SET expires_at = DATE_ADD(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", orderId);

        CompletableFuture<CourseOrderResponse> payment;
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = context.getBean(DataSource.class).getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT id FROM coupon_claim WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, fixture.claimId());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            payment = CompletableFuture.supplyAsync(
                    () -> service().pay(fixture.userId(), orderId), executor);
            Thread.sleep(1_500L);
            blocker.commit();
        } finally {
            executor.shutdown();
        }

        CourseOrderResponse result = payment.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(CourseOrderService.CLOSED);
        assertThat(result.paidAmount()).isNull();
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
    }

    @Test
    void paymentRechecksCouponExpiryAfterWaitingForClaimLock() throws Exception {
        Fixture fixture = fixture();
        CourseOrderResponse pending = service().create(fixture.userId(), fixture.courseId(), fixture.claimId());
        long orderId = Long.parseLong(pending.id());
        orderIds.add(orderId);

        CompletableFuture<CourseOrderResponse> payment;
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = context.getBean(DataSource.class).getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT id FROM coupon_claim WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, fixture.claimId());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            payment = CompletableFuture.supplyAsync(
                    () -> service().pay(fixture.userId(), orderId), executor);
            Thread.sleep(300L);
            jdbc().update("UPDATE coupon SET use_end_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
                    fixture.couponId());
            blocker.commit();
        } finally {
            executor.shutdown();
        }

        CourseOrderResponse result = payment.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(CourseOrderService.CLOSED);
        assertThat(result.paidAmount()).isNull();
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
    }

    @Test
    void creationRechecksCouponUsePeriodAfterWaitingForClaimLock() throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<CreateAttempt> creation;
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = context.getBean(DataSource.class).getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT id FROM coupon_claim WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, fixture.claimId());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            creation = CompletableFuture.supplyAsync(
                    () -> createWithCoupon(fixture.userId(), fixture.courseId(), fixture.claimId()), executor);
            Thread.sleep(300L);
            jdbc().update("UPDATE coupon SET use_end_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
                    fixture.couponId());
            blocker.commit();
        } finally {
            executor.shutdown();
        }

        CreateAttempt result = creation.get(10, TimeUnit.SECONDS);
        assertThat(result.response()).isNull();
        assertThat(result.errorCode()).isEqualTo("COUPON_NOT_IN_USE_PERIOD");
        assertThat(jdbc().queryForObject("""
                SELECT COUNT(*) FROM course_order WHERE user_id = ? AND course_id = ?
                """, Integer.class, fixture.userId(), fixture.courseId())).isZero();
        assertClaim(fixture.claimId(), "AVAILABLE", null, null);
    }

    private Fixture fixture() {
        long suffix = Math.abs(System.nanoTime() % 1_000_000);
        long courseId = 8_710_000_000L + suffix;
        long couponId = 8_720_000_000L + suffix;
        long claimId = 8_730_000_000L + suffix;
        long userId = 8_740_000_000L + suffix;
        courseIds.add(courseId);
        couponIds.add(couponId);
        claimIds.add(claimId);
        insertCourse(courseId, "交易闭环验收课程");
        jdbc().update("""
                INSERT INTO coupon (id, name, type, threshold_amount, discount_amount, total_stock,
                                    available_stock, claim_start_at, claim_end_at, use_start_at, use_end_at,
                                    per_user_limit, status, version)
                VALUES (?, '交易闭环验收券', 'NORMAL', 100.00, 30.00, 1, 0,
                        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 1 DAY),
                        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 1 DAY), 1, 'ACTIVE', 0)
                """, couponId);
        jdbc().update("""
                INSERT INTO coupon_claim (id, coupon_id, user_id, status, claimed_at)
                VALUES (?, ?, ?, 'AVAILABLE', NOW(3))
                """, claimId, couponId, userId);
        return new Fixture(userId, courseId, couponId, claimId);
    }

    private void assertReusedOrderNullableFieldsCleared(long orderId) {
        var row = jdbc().queryForMap("""
                SELECT coupon_claim_id, paid_amount, paid_at, cancelled_at, refunded_at
                FROM course_order WHERE id = ?
                """, orderId);
        assertThat(row.values()).containsOnlyNulls();
    }

    private void assertClaim(long claimId, String status, Long reservedOrderId, Long usedOrderId) {
        var row = jdbc().queryForMap("""
                SELECT status, reserved_order_id, used_order_id FROM coupon_claim WHERE id = ?
                """, claimId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("reserved_order_id")).isEqualTo(reservedOrderId);
        assertThat(row.get("used_order_id")).isEqualTo(usedOrderId);
    }

    private void runAfter(CountDownLatch start, Runnable action) {
        try {
            start.await(5, TimeUnit.SECONDS);
            action.run();
        } catch (com.learnhub.common.exception.BusinessException expectedRace) {
            assertThat(expectedRace.getCode()).isEqualTo("ORDER_STATUS_INVALID");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private CourseOrderResponse createAfter(CountDownLatch start, Fixture fixture) {
        try {
            start.await(5, TimeUnit.SECONDS);
            return service().create(fixture.userId(), fixture.courseId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private CreateAttempt createWithCouponAfter(CountDownLatch start, long userId, long courseId, long claimId) {
        try {
            start.await(5, TimeUnit.SECONDS);
            return createWithCoupon(userId, courseId, claimId);
        } catch (BusinessException exception) {
            return new CreateAttempt(null, exception.getCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private CreateAttempt createWithCoupon(long userId, long courseId, long claimId) {
        try {
            return new CreateAttempt(service().create(userId, courseId, claimId), null);
        } catch (BusinessException exception) {
            return new CreateAttempt(null, exception.getCode());
        }
    }

    private void insertCourse(long courseId, String title) {
        jdbc().update("""
                INSERT INTO course (id, category_id, title, instructor, price, status)
                VALUES (?, 1, ?, '验收讲师', 100.00, 'PUBLISHED')
                """, courseId, title);
    }

    private void deleteRows(String table, List<Long> ids) {
        for (Long id : List.copyOf(ids)) {
            jdbc().update("DELETE FROM " + table + " WHERE id = ?", id);
            ids.remove(id);
        }
    }

    private CourseOrderService service() { return context.getBean(CourseOrderService.class); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private String mysqlUrl() {
        return "jdbc:mysql://127.0.0.1:" + property("learnhub.commerce.it.mysql-port", "3306")
                + "/learnhub_commerce_it?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }
    private String property(String name, String fallback) { return System.getProperty(name, fallback); }

    private record Fixture(long userId, long courseId, long couponId, long claimId) {}
    private record CreateAttempt(CourseOrderResponse response, String errorCode) {}

    @Configuration
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = {CourseOrderMapper.class, CourseMapper.class,
            CouponClaimMapper.class, CouponMapper.class})
    @Import(CourseOrderService.class)
    static class CommerceTestApplication {}
}
