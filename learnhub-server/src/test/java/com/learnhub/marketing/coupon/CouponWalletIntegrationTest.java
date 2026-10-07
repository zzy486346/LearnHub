package com.learnhub.marketing.coupon;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.security.LearnHubPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/learnhub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=learnhub", "spring.datasource.password=learnhub",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "spring.rabbitmq.host=localhost", "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=learnhub", "spring.rabbitmq.password=learnhub",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "learnhub.rabbit.enabled=true", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.like.legacy-key-migration-enabled=false",
        "learnhub.storage.oss.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "learnhub.it.enabled", matches = "true")
class CouponWalletIntegrationTest {
    @Autowired CouponService service;
    @Autowired SeckillPersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    private final List<Long> couponIds = new ArrayList<>();
    private final long userId = 8_600_000_000L + Math.abs(System.nanoTime() % 1_000_000);

    @AfterEach
    void cleanup() {
        for (Long id : couponIds) {
            jdbc.update("DELETE FROM mq_consume_log WHERE message_id IN (SELECT message_id FROM seckill_order WHERE coupon_id = ?)", id);
            jdbc.update("DELETE FROM coupon_claim WHERE coupon_id = ?", id);
            jdbc.update("DELETE FROM seckill_order WHERE coupon_id = ?", id);
            jdbc.update("DELETE FROM coupon WHERE id = ?", id);
            var keys = redis.keys("learnhub:coupon:{" + id + "}:*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
    }

    @Test
    void normalAndSeckillClaimsAppearInOwnWalletWithAmountAndExpiry() throws Exception {
        Long normalId = coupon(false, false);
        Long seckillId = coupon(true, false);
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(userId, "wallet-it"), null, List.of());
        mvc.perform(post("/api/coupons/" + normalId + "/claim").with(authentication(user))).andExpect(status().isOk());
        mvc.perform(post("/api/coupons/" + normalId + "/claim").with(authentication(user))).andExpect(status().isOk());
        mvc.perform(post("/api/coupons/" + seckillId + "/seckill").with(authentication(user))).andExpect(status().isOk());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && service.status(seckillId, userId).status() == CouponClaim.Status.RESERVED) Thread.sleep(100);
        assertEquals(CouponClaim.Status.CONFIRMED, service.status(seckillId, userId).status());
        List<MyCoupon> wallet = service.mine(userId);
        assertEquals(2, wallet.size());
        assertTrue(wallet.stream().allMatch(item -> "AVAILABLE".equals(item.status())));
        assertTrue(wallet.stream().allMatch(item -> new BigDecimal("30.00").equals(item.discountAmount())));
        assertTrue(service.mine(userId + 1).isEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim WHERE user_id = ?", Integer.class, userId));
        mvc.perform(get("/api/coupons/me")).andExpect(status().isForbidden());
        String json = mvc.perform(get("/api/coupons/me").with(authentication(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"couponId\":\"" + seckillId + "\""));
        jdbc.update("UPDATE coupon SET use_end_at = DATE_SUB(NOW(3), INTERVAL 1 DAY) WHERE id = ?", normalId);
        assertEquals("EXPIRED", service.mine(userId).stream().filter(item -> item.couponId().equals(normalId)).findFirst().orElseThrow().status());
    }

    @Test
    void endedActivityIsVisibleButCannotReserveStock() throws Exception {
        Long id = coupon(true, true);
        assertEquals("ENDED", service.list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow().status());
        assertEquals("优惠券活动已结束", assertThrows(com.learnhub.common.exception.BusinessException.class, () -> service.seckill(id, userId)).getMessage());
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(userId, "wallet-it"), null, List.of());
        mvc.perform(post("/api/coupons/" + id + "/seckill").with(authentication(user)))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("COUPON_ENDED"));
        assertNull(service.status(id, userId));
        assertEquals(2, jdbc.queryForObject("SELECT available_stock FROM coupon WHERE id = ?", Integer.class, id));
        mvc.perform(get("/api/coupons")).andExpect(status().isOk());
    }

    private Long coupon(boolean seckill, boolean ended) {
        CouponEntity entity = new CouponEntity();
        entity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        couponIds.add(entity.getId());
        entity.setName("wallet-it"); entity.setType(seckill ? "SECKILL" : "NORMAL");
        entity.setDiscountAmount(new BigDecimal("30.00")); entity.setThresholdAmount(new BigDecimal("100.00"));
        entity.setTotalStock(2); entity.setAvailableStock(2); entity.setStatus("ACTIVE");
        entity.setPerUserLimit(1); entity.setVersion(0);
        entity.setClaimStartAt(LocalDateTime.now().minusDays(2));
        entity.setClaimEndAt(ended ? LocalDateTime.now().minusDays(1) : LocalDateTime.now().plusDays(1));
        entity.setUseStartAt(LocalDateTime.now().minusDays(1)); entity.setUseEndAt(LocalDateTime.now().plusDays(2));
        entity.setCreatedAt(LocalDateTime.now()); entity.setUpdatedAt(LocalDateTime.now());
        persistence.createCoupon(entity);
        return entity.getId();
    }

    @Test
    void pendingAndFailedRequestsAreVisibleWithoutPretendingTheyAreOwnedCoupons() {
        Long id = coupon(true, false);
        String requestId = java.util.UUID.randomUUID().toString();
        persistence.createPendingOrder(requestId, id, userId, LocalDateTime.now());
        assertEquals("RESERVED", service.mine(userId).get(0).status());
        assertNull(persistence.findClaim(id, userId));
        persistence.markFailed(requestId, "test failure");
        assertEquals("REJECTED", service.mine(userId).get(0).status());
        assertTrue(service.mine(userId + 1).isEmpty());
    }
}
