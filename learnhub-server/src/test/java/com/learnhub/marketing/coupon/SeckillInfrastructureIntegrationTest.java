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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/learnhub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=learnhub",
        "spring.datasource.password=learnhub",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=learnhub",
        "spring.rabbitmq.password=learnhub",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
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
        redis.delete(List.of(stockKey(), usersKey(), reservationsKey()));
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
                    } catch (IllegalStateException expectedWhenSoldOut) {
                        assertEquals("Coupon sold out", expectedWhenSoldOut.getMessage());
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
