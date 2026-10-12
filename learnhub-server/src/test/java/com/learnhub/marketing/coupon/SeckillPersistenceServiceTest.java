package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "seckill-test"), SeckillOrderEntity.class);
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
    void reactivatesCompensatedFailedOrderWithANewRequestId() {
        LocalDateTime reservedAt = LocalDateTime.of(2026, 10, 11, 19, 30);
        SeckillOrderEntity failed = order(101L, "old-request", "FAILED", true);
        SeckillOrderEntity retried = order(101L, "new-request", "PENDING", false);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(failed, retried);
        when(orderMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

        SeckillOrderEntity result = service.createPendingOrder("new-request", 7L, 21L, reservedAt);

        assertSame(retried, result);
        verify(orderMapper, never()).insert(any(SeckillOrderEntity.class));
        verify(orderMapper).update(eq(null), any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsRetryUntilFailedOrderCompensationCompletes() {
        SeckillOrderEntity failed = order(101L, "old-request", "FAILED", false);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(failed);

        com.learnhub.common.exception.BusinessException error = assertThrows(
                com.learnhub.common.exception.BusinessException.class,
                () -> service.createPendingOrder("new-request", 7L, 21L, LocalDateTime.now()));

        assertEquals("COUPON_ALREADY_CLAIMED", error.getCode());
        verify(orderMapper, never()).insert(any(SeckillOrderEntity.class));
        verify(orderMapper, never()).update(eq(null), any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleMessageIsAcknowledgedWithoutConfirmingReactivatedOrder() {
        SeckillMessage stale = new SeckillMessage(
                "old-request", "old-request", 7L, 21L, LocalDateTime.now(), 1);
        SeckillOrderEntity retried = order(101L, "new-request", "PENDING", false);
        SeckillPersistenceService isolated = spy(service);
        doReturn(null).when(isolated).findOrder("old-request");
        doReturn(retried).when(isolated).findOrder(7L, 21L);

        SeckillOrderEntity result = isolated.confirm(stale);

        assertSame(retried, result);
        verify(couponMapper, never()).decrementAvailableStock(any());
        verify(claimMapper, never()).insert(any(CouponClaimEntity.class));
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

    private SeckillOrderEntity order(Long id, String requestId, String status, boolean compensated) {
        SeckillOrderEntity order = new SeckillOrderEntity();
        order.setId(id);
        order.setRequestId(requestId);
        order.setCouponId(7L);
        order.setUserId(21L);
        order.setStatus(status);
        order.setCompensated(compensated);
        return order;
    }
}
