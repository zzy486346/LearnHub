package com.learnhub.marketing.coupon;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CouponServiceTest {
    @Test
    void localSeckillPreventsDuplicateAndOversell() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        CouponService service = new CouponService(redisProvider, rabbitProvider, redissonProvider, false);
        Coupon coupon = service.create(new CouponRequests.Create("MVP", 1, Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60), true));

        assertEquals(CouponClaim.Status.CONFIRMED, service.seckill(coupon.id(), 1L).status());
        assertThrows(IllegalStateException.class, () -> service.seckill(coupon.id(), 1L));
        assertThrows(IllegalStateException.class, () -> service.seckill(coupon.id(), 2L));
    }
}
