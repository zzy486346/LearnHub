package com.learnhub.marketing.coupon;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.url=jdbc:mysql://${learnhub.seckill.it.mysql-host:127.0.0.1}:${learnhub.seckill.it.mysql-port:3306}/${learnhub.seckill.it.mysql-database:learnhub}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=learnhub",
        "spring.datasource.password=learnhub",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=${learnhub.seckill.it.redis-host:127.0.0.1}",
        "spring.data.redis.port=${learnhub.seckill.it.redis-port:6379}",
        "spring.rabbitmq.host=${learnhub.seckill.it.rabbit-host:127.0.0.1}",
        "spring.rabbitmq.port=${learnhub.seckill.it.rabbit-port:5672}",
        "spring.rabbitmq.username=learnhub",
        "spring.rabbitmq.password=learnhub",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.listener.simple.retry.max-attempts=1",
        "learnhub.rabbit.enabled=true",
        "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.storage.oss.enabled=false"
})
@EnabledIfSystemProperty(named = "learnhub.it.enabled", matches = "true")
class SeckillInfrastructureIntegrationTest {
    private static final int STOCK = 5;
    private static final int REQUESTS = 12;

    @Autowired
    private CouponService couponService;

    @Autowired
    private SeckillPersistenceService persistence;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private Long couponId;

    @AfterEach
    void cleanUp() {
        if (couponId == null) return;
        jdbc.update("""
                DELETE log FROM mq_consume_log log
                JOIN seckill_order orders ON orders.request_id = log.message_id
                WHERE orders.coupon_id = ?
                """, couponId);
        jdbc.update("DELETE FROM coupon_claim WHERE coupon_id = ?", couponId);
        jdbc.update("DELETE FROM seckill_order WHERE coupon_id = ?", couponId);
        jdbc.update("DELETE FROM coupon WHERE id = ?", couponId);
        var ownedKeys = redis.keys("learnhub:coupon:{" + couponId + "}:*");
        if (ownedKeys != null && !ownedKeys.isEmpty()) redis.delete(ownedKeys);
    }

    @Test
    void concurrentRequestsDoNotOversellAndDuplicateConfirmationIsIdempotent() throws Exception {
        Coupon coupon = couponService.create(new CouponRequests.Create(
                "infrastructure-it-" + System.nanoTime(), STOCK,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(120), true));
        couponId = coupon.id();

        AtomicInteger accepted = submitConcurrentRequests();
        awaitSuccessfulPersistence();

        assertEquals(STOCK, accepted.get());
        assertEquals(STOCK, count("seckill_order", "status = 'SUCCESS'"));
        assertEquals(STOCK, count("coupon_claim", "1 = 1"));
        assertEquals(0, availableStock());

        SeckillMessage alreadyConsumed = firstSuccessfulMessage();
        assertNotNull(couponService.confirm(alreadyConsumed));

        assertEquals(STOCK, count("seckill_order", "status = 'SUCCESS'"));
        assertEquals(STOCK, count("coupon_claim", "1 = 1"));
        assertEquals(0, availableStock());
    }

    @Test
    void consecutiveFailuresCompensateAndThirdAttemptSucceedsWithoutOldMessageInterference() throws Exception {
        Coupon coupon = couponService.create(new CouponRequests.Create(
                "retry-infrastructure-it-" + System.nanoTime(), 1,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(120), true));
        couponId = coupon.id();
        long userId = 9_900_001L;
        jdbc.update("UPDATE coupon SET available_stock = 0 WHERE id = ?", couponId);

        couponService.seckill(couponId, userId);
        SeckillOrderEntity firstPending = persistence.findOrder(couponId, userId);
        couponService.failAndCompensate(message(firstPending), "simulated first attempt failure");
        SeckillOrderEntity firstFailure = awaitCompensatedFailure(null, userId);
        assertCompensatedRedisState(userId);

        couponService.seckill(couponId, userId);
        SeckillOrderEntity secondPending = persistence.findOrder(couponId, userId);
        couponService.failAndCompensate(message(secondPending), "simulated second attempt failure");
        SeckillOrderEntity secondFailure = awaitCompensatedFailure(firstFailure.getRequestId(), userId);
        assertNotEquals(firstFailure.getRequestId(), secondFailure.getRequestId());
        assertCompensatedRedisState(userId);

        jdbc.update("UPDATE coupon SET available_stock = 1 WHERE id = ?", couponId);
        couponService.seckill(couponId, userId);
        SeckillOrderEntity retried = persistence.findOrder(couponId, userId);
        assertNotNull(retried);
        assertNotEquals(secondFailure.getRequestId(), retried.getRequestId());

        SeckillMessage delayedOldMessage = new SeckillMessage(
                firstFailure.getRequestId(), firstFailure.getRequestId(), couponId, userId,
                firstFailure.getReservedAt(), 1);
        assertEquals(retried.getRequestId(), couponService.confirm(delayedOldMessage).getRequestId());
        couponService.failAndCompensate(delayedOldMessage, "delayed old message reached DLQ");

        awaitSingleRetrySuccess();
        assertEquals(1, count("seckill_order", "status = 'SUCCESS'"));
        assertEquals(1, count("coupon_claim", "user_id = " + userId));
        assertEquals(0, availableStock());
        assertEquals("0", redis.opsForValue().get(stockKey()));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey(), Long.toString(userId))));
        assertEquals(0L, redis.opsForHash().size(reservationsKey()));
        assertEquals(retried.getRequestId(), persistence.findOrder(couponId, userId).getRequestId());
    }

    private AtomicInteger submitConcurrentRequests() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                long userId = 9_000_000L + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        couponService.seckill(couponId, userId);
                        accepted.incrementAndGet();
                    } catch (com.learnhub.common.exception.BusinessException expectedWhenSoldOut) {
                        assertEquals("COUPON_SOLD_OUT", expectedWhenSoldOut.getCode());
                    }
                    return null;
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) fail("Concurrent requests did not become ready");
            start.countDown();
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
            return accepted;
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitSuccessfulPersistence() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (count("seckill_order", "status = 'SUCCESS'") == STOCK
                    && count("coupon_claim", "1 = 1") == STOCK) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for RabbitMQ consumers to persist all successful reservations");
    }

    private void awaitSingleRetrySuccess() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (count("seckill_order", "status = 'SUCCESS'") == 1
                    && count("coupon_claim", "1 = 1") == 1) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for the retried reservation to succeed");
    }

    private SeckillOrderEntity awaitCompensatedFailure(String previousRequestId, long userId)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            SeckillOrderEntity order = persistence.findOrder(couponId, userId);
            if (order != null && "FAILED".equals(order.getStatus())
                    && Boolean.TRUE.equals(order.getCompensated())
                    && (previousRequestId == null || !previousRequestId.equals(order.getRequestId()))) {
                return order;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for failed reservation compensation");
        throw new IllegalStateException("unreachable");
    }

    private void assertCompensatedRedisState(long userId) {
        assertEquals("1", redis.opsForValue().get(stockKey()));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey(), Long.toString(userId))));
        assertEquals(0L, redis.opsForHash().size(reservationsKey()));
    }

    private SeckillMessage message(SeckillOrderEntity order) {
        return new SeckillMessage(order.getRequestId(), order.getRequestId(), order.getCouponId(),
                order.getUserId(), order.getReservedAt(), 1);
    }

    private int count(String table, String predicate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE coupon_id = ? AND " + predicate,
                Integer.class, couponId);
        return count == null ? 0 : count;
    }

    private int availableStock() {
        Integer stock = jdbc.queryForObject(
                "SELECT available_stock FROM coupon WHERE id = ?", Integer.class, couponId);
        return stock == null ? -1 : stock;
    }

    private SeckillMessage firstSuccessfulMessage() {
        return jdbc.queryForObject("""
                        SELECT request_id, coupon_id, user_id, reserved_at
                        FROM seckill_order
                        WHERE coupon_id = ? AND status = 'SUCCESS'
                        ORDER BY created_at
                        LIMIT 1
                        """,
                (rs, rowNum) -> new SeckillMessage(
                        rs.getString("request_id"),
                        rs.getString("request_id"),
                        rs.getLong("coupon_id"),
                        rs.getLong("user_id"),
                        rs.getTimestamp("reserved_at").toLocalDateTime(),
                        1),
                couponId);
    }

    private String stockKey() {
        return "learnhub:coupon:{" + couponId + "}:stock";
    }

    private String usersKey() {
        return "learnhub:coupon:{" + couponId + "}:users";
    }

    private String reservationsKey() {
        return "learnhub:coupon:{" + couponId + "}:reservations";
    }
}
