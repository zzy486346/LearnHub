package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillPersistenceServiceTest {
    private CouponMapper couponMapper;
    private CouponClaimMapper claimMapper;
    private SeckillOrderMapper orderMapper;
    private MqConsumeLogMapper consumeLogMapper;
    private SeckillPersistenceService service;

    @BeforeEach
    void setUp() {
        couponMapper = mock(CouponMapper.class);
        claimMapper = mock(CouponClaimMapper.class);
        orderMapper = mock(SeckillOrderMapper.class);
        consumeLogMapper = mock(MqConsumeLogMapper.class);
        service = new SeckillPersistenceService(couponMapper, claimMapper, orderMapper, consumeLogMapper);
    }

    @Test
    void createsOrderInPendingStateBeforeMessagePublication() {
        LocalDateTime reservedAt = LocalDateTime.of(2026, 9, 29, 10, 30);

        service.createPendingOrder("request-1", 7L, 21L, reservedAt);

        ArgumentCaptor<SeckillOrderEntity> captor = ArgumentCaptor.forClass(SeckillOrderEntity.class);
        verify(orderMapper).insert(captor.capture());
        SeckillOrderEntity order = captor.getValue();
        assertEquals("PENDING", order.getStatus());
        assertEquals("PENDING", order.getPublishStatus());
        assertEquals(0, order.getPublishAttempts());
        assertFalse(order.getCompensated());
        assertEquals(reservedAt, order.getReservedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsDatabaseMutationWhenMessageWasAlreadyConsumed() {
        SeckillOrderEntity pending = new SeckillOrderEntity();
        pending.setRequestId("request-1");
        pending.setCouponId(7L);
        pending.setUserId(21L);
        pending.setStatus("PENDING");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        MqConsumeLogEntity existingLog = new MqConsumeLogEntity();
        existingLog.setMessageId("message-1");
        existingLog.setConsumerName(SeckillPersistenceService.CONSUMER_NAME);
        when(consumeLogMapper.selectOne(any(Wrapper.class))).thenReturn(existingLog);
        SeckillMessage message = new SeckillMessage(
                "message-1", "request-1", 7L, 21L, LocalDateTime.now(), 1);

        SeckillOrderEntity result = service.confirm(message);

        assertSame(pending, result);
        verify(couponMapper, never()).decrementAvailableStock(any());
        verify(claimMapper, never()).insert(any(CouponClaimEntity.class));
        verify(orderMapper, never()).update(any(), any());
    }
}
