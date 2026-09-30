package com.learnhub.marketing.coupon;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceTest {
    private SeckillPersistenceService persistence;
    private StringRedisTemplate redis;
    private RabbitTemplate rabbit;

    @BeforeEach
    void setUp() {
        persistence = mock(SeckillPersistenceService.class);
        redis = mock(StringRedisTemplate.class);
        rabbit = mock(RabbitTemplate.class);

        CouponEntity activeCoupon = new CouponEntity();
        activeCoupon.setId(7L);
        activeCoupon.setType("SECKILL");
        activeCoupon.setStatus("ACTIVE");
        activeCoupon.setClaimStartAt(LocalDateTime.now().minusMinutes(1));
        activeCoupon.setClaimEndAt(LocalDateTime.now().plusMinutes(1));
        when(persistence.findCoupon(7L)).thenReturn(activeCoupon);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsPendingOrderAfterRedisReservationSucceeds() {
        CouponService service = service(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L, 1L);
        SeckillOrderEntity pending = order("request-1", "PENDING", false);
        when(persistence.createPendingOrder(any(), eq(7L), eq(21L), any())).thenReturn(pending);
        when(persistence.confirm(any(SeckillMessage.class))).thenReturn(pending);
        when(persistence.findOrder(any(String.class))).thenReturn(pending);

        CouponClaim result = service.seckill(7L, 21L);

        assertEquals(CouponClaim.Status.RESERVED, result.status());
        InOrder sequence = inOrder(redis, persistence);
        sequence.verify(redis).execute(any(RedisScript.class), eq(List.of(
                "learnhub:coupon:{7}:stock", "learnhub:coupon:{7}:users",
                "learnhub:coupon:{7}:reservations")), any(Object[].class));
        sequence.verify(persistence).createPendingOrder(any(), eq(7L), eq(21L), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsRequestWhenRedisReportsSoldOut() {
        CouponService service = service(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.seckill(7L, 21L));

        assertEquals("Coupon sold out", error.getMessage());
        verify(persistence, never()).createPendingOrder(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsRequestWhenRedisReportsDuplicateClaim() {
        CouponService service = service(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.seckill(7L, 21L));

        assertEquals("Coupon already claimed", error.getMessage());
        verify(persistence, never()).createPendingOrder(any(), any(), any(), any());
    }

    @Test
    void marksPublishFailedWhenRabbitSendThrows() {
        CouponService service = service(true);
        SeckillMessage message = message("request-1");
        when(persistence.markPublishing(eq("request-1"), any())).thenReturn(1);
        SeckillOrderEntity publishing = order("request-1", "PENDING", false);
        publishing.setPublishAttempts(1);
        when(persistence.findOrder("request-1")).thenReturn(publishing);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbit).convertAndSend(eq(CouponService.EXCHANGE), eq(CouponService.ROUTING_KEY),
                        eq(message), any(CorrelationData.class));

        service.publish(message);

        verify(persistence).markPublishFailed("request-1", 1, "broker unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesDeferredCompensationUntilRedisRecovers() {
        CouponService service = service(false);
        SeckillMessage message = message("request-1");
        SeckillOrderEntity failed = order("request-1", "FAILED", false);
        when(persistence.findOrder("request-1")).thenReturn(failed);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn(1L);

        service.failAndCompensate(message, "consumer failed");
        verify(persistence, never()).markCompensated("request-1");

        service.failAndCompensate(message, "consumer failed");
        verify(persistence).markCompensated("request-1");
    }

    private CouponService service(boolean rabbitEnabled) {
        return new CouponService(persistence, provider(redis), provider(rabbit),
                provider((RedissonClient) null), rabbitEnabled, 3,
                Duration.ofMinutes(1), Duration.ofMinutes(1));
    }

    private SeckillMessage message(String requestId) {
        return new SeckillMessage(requestId, requestId, 7L, 21L, LocalDateTime.now(), 1);
    }

    private SeckillOrderEntity order(String requestId, String status, boolean compensated) {
        SeckillOrderEntity order = new SeckillOrderEntity();
        order.setRequestId(requestId);
        order.setCouponId(7L);
        order.setUserId(21L);
        order.setStatus(status);
        order.setCompensated(compensated);
        order.setReservedAt(LocalDateTime.now());
        return order;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
