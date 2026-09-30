package com.learnhub.marketing.coupon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeckillPersistenceService {
    static final String CONSUMER_NAME = "coupon-seckill";

    private final CouponMapper couponMapper;
    private final CouponClaimMapper claimMapper;
    private final SeckillOrderMapper orderMapper;
    private final MqConsumeLogMapper consumeLogMapper;

    public SeckillPersistenceService(CouponMapper couponMapper,
                                     CouponClaimMapper claimMapper,
                                     SeckillOrderMapper orderMapper,
                                     MqConsumeLogMapper consumeLogMapper) {
        this.couponMapper = couponMapper;
        this.claimMapper = claimMapper;
        this.orderMapper = orderMapper;
        this.consumeLogMapper = consumeLogMapper;
    }

    @Transactional
    public CouponEntity createCoupon(CouponEntity coupon) {
        couponMapper.insert(coupon);
        return coupon;
    }

    @Transactional
    public CouponClaimEntity claimNormally(Long couponId, Long userId, LocalDateTime claimedAt) {
        CouponClaimEntity existing = findClaim(couponId, userId);
        if (existing != null) return existing;
        if (couponMapper.decrementAvailableStock(couponId) != 1) {
            throw new IllegalStateException("Coupon sold out or inactive");
        }
        CouponClaimEntity claim = new CouponClaimEntity();
        claim.setCouponId(couponId);
        claim.setUserId(userId);
        claim.setStatus("AVAILABLE");
        claim.setClaimedAt(claimedAt);
        claim.setCreatedAt(claimedAt);
        claim.setUpdatedAt(claimedAt);
        claimMapper.insert(claim);
        return claim;
    }

    @Transactional
    public SeckillOrderEntity createPendingOrder(String requestId, Long couponId, Long userId,
                                                   LocalDateTime reservedAt) {
        SeckillOrderEntity order = new SeckillOrderEntity();
        order.setRequestId(requestId);
        order.setCouponId(couponId);
        order.setUserId(userId);
        order.setStatus("PENDING");
        order.setPublishStatus("PENDING");
        order.setPublishAttempts(0);
        order.setCompensated(false);
        order.setReservedAt(reservedAt);
        order.setNextRetryAt(reservedAt);
        order.setCreatedAt(reservedAt);
        order.setUpdatedAt(reservedAt);
        orderMapper.insert(order);
        return order;
    }

    @Transactional
    public SeckillOrderEntity confirm(SeckillMessage message) {
        SeckillOrderEntity order = findOrder(message.requestId());
        if (order == null) throw new IllegalStateException("Unknown seckill request: " + message.requestId());
        if (message.version() != 1
                || !message.couponId().equals(order.getCouponId())
                || !message.userId().equals(order.getUserId())) {
            throw new IllegalArgumentException("Seckill message does not match persisted request: " + message.requestId());
        }
        if ("SUCCESS".equals(order.getStatus())) return order;
        if ("FAILED".equals(order.getStatus())) {
            throw new IllegalStateException("Seckill request already failed: " + message.requestId());
        }

        LocalDateTime now = LocalDateTime.now();
        MqConsumeLogEntity existingLog = consumeLogMapper.selectOne(new LambdaQueryWrapper<MqConsumeLogEntity>()
                .eq(MqConsumeLogEntity::getMessageId, message.messageId())
                .eq(MqConsumeLogEntity::getConsumerName, CONSUMER_NAME)
                .last("LIMIT 1"));
        if (existingLog != null) {
            return findOrder(message.requestId());
        }
        MqConsumeLogEntity consumeLog = new MqConsumeLogEntity();
        consumeLog.setMessageId(message.messageId());
        consumeLog.setConsumerName(CONSUMER_NAME);
        consumeLog.setStatus("PROCESSING");
        consumeLog.setCreatedAt(now);
        consumeLog.setUpdatedAt(now);
        try {
            consumeLogMapper.insert(consumeLog);
        } catch (DuplicateKeyException duplicate) {
            return findOrder(message.requestId());
        }
        if (couponMapper.decrementAvailableStock(order.getCouponId()) != 1) {
            throw new IllegalStateException("Database coupon stock exhausted: " + order.getCouponId());
        }

        CouponClaimEntity claim = new CouponClaimEntity();
        claim.setCouponId(order.getCouponId());
        claim.setUserId(order.getUserId());
        claim.setStatus("AVAILABLE");
        claim.setClaimedAt(order.getReservedAt());
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        claimMapper.insert(claim);

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, message.requestId())
                .eq(SeckillOrderEntity::getStatus, "PENDING")
                .set(SeckillOrderEntity::getStatus, "SUCCESS")
                .set(SeckillOrderEntity::getPublishStatus, "CONFIRMED")
                .set(SeckillOrderEntity::getUpdatedAt, now));
        if (updated != 1) throw new IllegalStateException("Seckill order state changed concurrently");

        consumeLogMapper.update(null, new LambdaUpdateWrapper<MqConsumeLogEntity>()
                .eq(MqConsumeLogEntity::getMessageId, message.messageId())
                .eq(MqConsumeLogEntity::getConsumerName, CONSUMER_NAME)
                .set(MqConsumeLogEntity::getStatus, "SUCCESS")
                .set(MqConsumeLogEntity::getUpdatedAt, now));
        return findOrder(message.requestId());
    }

    public CouponEntity findCoupon(Long couponId) {
        return couponMapper.selectById(couponId);
    }

    public List<CouponEntity> listCoupons() {
        return couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                .orderByDesc(CouponEntity::getCreatedAt));
    }

    public List<CouponEntity> listActiveSeckillCoupons() {
        return couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getType, "SECKILL")
                .eq(CouponEntity::getStatus, "ACTIVE"));
    }

    public List<SeckillOrderEntity> listReservedOrders(Long couponId) {
        return orderMapper.selectList(new LambdaQueryWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getCouponId, couponId)
                .and(q -> q.in(SeckillOrderEntity::getStatus, "PENDING", "SUCCESS")
                        .or(failed -> failed.eq(SeckillOrderEntity::getStatus, "FAILED")
                                .eq(SeckillOrderEntity::getCompensated, false))));
    }

    @Transactional(readOnly = true)
    public SeckillRedisSnapshot loadRedisSnapshot(Long couponId) {
        CouponEntity coupon = couponMapper.selectById(couponId);
        if (coupon == null) throw new IllegalArgumentException("Coupon not found: " + couponId);
        return new SeckillRedisSnapshot(coupon, listReservedOrders(couponId));
    }

    public CouponClaimEntity findClaim(Long couponId, Long userId) {
        return claimMapper.selectOne(new LambdaQueryWrapper<CouponClaimEntity>()
                .eq(CouponClaimEntity::getCouponId, couponId)
                .eq(CouponClaimEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    public SeckillOrderEntity findOrder(String requestId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    public SeckillOrderEntity findOrder(Long couponId, Long userId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getCouponId, couponId)
                .eq(SeckillOrderEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    @Transactional
    public int markPublishing(String requestId, LocalDateTime nextRetryAt) {
        LocalDateTime now = LocalDateTime.now();
        return orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .eq(SeckillOrderEntity::getStatus, "PENDING")
                .le(SeckillOrderEntity::getNextRetryAt, now)
                .setSql("publish_attempts = publish_attempts + 1")
                .set(SeckillOrderEntity::getPublishStatus, "PUBLISHING")
                .set(SeckillOrderEntity::getNextRetryAt, nextRetryAt)
                .set(SeckillOrderEntity::getUpdatedAt, now));
    }

    @Transactional
    public void markPublished(String requestId, int publishAttempt, LocalDateTime nextRetryAt) {
        orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .eq(SeckillOrderEntity::getStatus, "PENDING")
                .eq(SeckillOrderEntity::getPublishStatus, "PUBLISHING")
                .eq(SeckillOrderEntity::getPublishAttempts, publishAttempt)
                .set(SeckillOrderEntity::getPublishStatus, "SENT")
                .set(SeckillOrderEntity::getNextRetryAt, nextRetryAt)
                .set(SeckillOrderEntity::getFailureReason, null)
                .set(SeckillOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    @Transactional
    public void markPublishFailed(String requestId, int publishAttempt, String reason) {
        orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .eq(SeckillOrderEntity::getStatus, "PENDING")
                .eq(SeckillOrderEntity::getPublishStatus, "PUBLISHING")
                .eq(SeckillOrderEntity::getPublishAttempts, publishAttempt)
                .set(SeckillOrderEntity::getPublishStatus, "FAILED")
                .set(SeckillOrderEntity::getFailureReason, truncate(reason))
                .set(SeckillOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    @Transactional
    public boolean markFailed(String requestId, String reason) {
        return orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .eq(SeckillOrderEntity::getStatus, "PENDING")
                .set(SeckillOrderEntity::getStatus, "FAILED")
                .set(SeckillOrderEntity::getFailureReason, truncate(reason))
                .set(SeckillOrderEntity::getUpdatedAt, LocalDateTime.now())) == 1;
    }

    @Transactional
    public void markCompensated(String requestId) {
        orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getRequestId, requestId)
                .eq(SeckillOrderEntity::getStatus, "FAILED")
                .set(SeckillOrderEntity::getCompensated, true)
                .set(SeckillOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    public List<SeckillOrderEntity> findRecoverableOrders(int limit) {
        return orderMapper.selectPage(new Page<>(1, limit), new LambdaQueryWrapper<SeckillOrderEntity>()
                        .and(q -> q
                                .eq(SeckillOrderEntity::getStatus, "FAILED")
                                .eq(SeckillOrderEntity::getCompensated, false)
                                .or(p -> p.eq(SeckillOrderEntity::getStatus, "PENDING")
                                        .in(SeckillOrderEntity::getPublishStatus, "PENDING", "PUBLISHING", "FAILED", "SENT")
                                        .le(SeckillOrderEntity::getNextRetryAt, LocalDateTime.now())))
                        .orderByAsc(SeckillOrderEntity::getCreatedAt))
                .getRecords();
    }

    private String truncate(String reason) {
        if (reason == null) return null;
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
