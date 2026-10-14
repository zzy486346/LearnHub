package com.learnhub.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.marketing.coupon.CouponClaimEntity;
import com.learnhub.marketing.coupon.CouponClaimMapper;
import com.learnhub.marketing.coupon.CouponEntity;
import com.learnhub.marketing.coupon.CouponMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseOrderService {
    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String CLOSED = "CLOSED";
    public static final String REFUNDED = "REFUNDED";
    public static final String LOCAL_DEMO = "LOCAL_DEMO";
    public static final String DISABLED = "DISABLED";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final CourseOrderMapper orderMapper;
    private final CourseMapper courseMapper;
    private final CouponClaimMapper claimMapper;
    private final CouponMapper couponMapper;
    private final String configuredPaymentMode;
    private final Duration pendingTimeout;
    private final Environment environment;

    public CourseOrderService(CourseOrderMapper orderMapper, CourseMapper courseMapper,
                              CouponClaimMapper claimMapper, CouponMapper couponMapper,
                              @Value("${learnhub.payment.mode:DISABLED}") String configuredPaymentMode,
                              @Value("${learnhub.payment.pending-timeout:PT30M}") Duration pendingTimeout,
                              Environment environment) {
        this.orderMapper = orderMapper;
        this.courseMapper = courseMapper;
        this.claimMapper = claimMapper;
        this.couponMapper = couponMapper;
        this.configuredPaymentMode = configuredPaymentMode == null
                ? DISABLED : configuredPaymentMode.trim().toUpperCase(Locale.ROOT);
        this.pendingTimeout = pendingTimeout;
        this.environment = environment;
    }

    public boolean hasPurchased(Long userId, Long courseId) {
        if (userId == null) return false;
        return orderMapper.selectCount(new LambdaQueryWrapper<CourseOrder>()
                .eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getCourseId, courseId)
                .eq(CourseOrder::getStatus, PAID)) > 0;
    }

    @Transactional
    public List<CourseOrderResponse> listMine(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<CourseOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<CourseOrder>()
                .eq(CourseOrder::getUserId, userId)
                .orderByDesc(CourseOrder::getCreatedAt)
                .orderByDesc(CourseOrder::getId));
        orders.stream().filter(order -> isExpiredPending(order, now))
                .forEach(order -> closePending(order, now, CLOSED));
        return orders.stream().map(this::toResponse).toList();
    }

    @Transactional
    public CourseOrderResponse create(Long userId, Long courseId, Long couponClaimId) {
        Course course = courseMapper.selectPublishedForPurchase(courseId);
        if (course == null) throw new BusinessException("COURSE_NOT_FOUND", "课程不存在或未上架");
        BigDecimal originalAmount = money(course.getPrice());
        CourseOrder snapshot = orderMapper.selectUserCourse(userId, courseId);
        CourseOrder order = snapshot == null ? null : requireOwnedForUpdate(userId, snapshot.getId());
        LocalDateTime now = LocalDateTime.now();
        if (order != null && PAID.equals(order.getStatus())) return toResponse(order);
        if (order != null && isExpiredPending(order, now)) closePending(order, now, CLOSED);
        if (order != null && PENDING.equals(order.getStatus())
                && Objects.equals(order.getCouponClaimId(), couponClaimId)) return toResponse(order);

        boolean insert = order == null;
        if (insert) {
            order = new CourseOrder();
            order.setId(IdWorker.getId());
            order.setUserId(userId);
            order.setCourseId(courseId);
        } else if (PENDING.equals(order.getStatus())) {
            releaseReservedClaim(order, now);
        }
        preparePending(order, course, originalAmount, now);
        if (couponClaimId != null) applyCoupon(order, userId, couponClaimId, originalAmount);
        if (insert) {
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException duplicate) {
                throw new BusinessException("ORDER_CONCURRENT_CREATE", "订单状态已变化，请刷新后重试");
            }
        } else {
            orderMapper.updateById(order);
        }
        return toResponse(order);
    }

    @Transactional
    public CourseOrderResponse create(Long userId, Long courseId) {
        return create(userId, courseId, null);
    }

    @Transactional
    public CourseOrderResponse pay(Long userId, Long orderId) {
        CourseOrder snapshot = orderMapper.selectById(orderId);
        if (snapshot == null || !userId.equals(snapshot.getUserId())) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }
        if (PAID.equals(snapshot.getStatus())) return toResponse(snapshot);
        if (courseMapper.selectPublishedForPurchase(snapshot.getCourseId()) == null) {
            throw new BusinessException("COURSE_NOT_FOUND", "课程已下架，无法继续支付");
        }
        CourseOrder order = requireOwnedForUpdate(userId, orderId);
        if (PAID.equals(order.getStatus())) return toResponse(order);
        if (!PENDING.equals(order.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "订单不是待支付状态");
        }
        LocalDateTime now = LocalDateTime.now();
        if (isExpiredPending(order, now)) {
            closePending(order, now, CLOSED);
            return toResponse(order);
        }
        if (!LOCAL_DEMO.equals(effectivePaymentMode()) || !LOCAL_DEMO.equals(order.getPaymentMode())) {
            throw new BusinessException("PAYMENT_UNAVAILABLE", "当前环境未开放支付");
        }
        try {
            ClaimUseOutcome claimUse = useReservedClaim(order, userId);
            now = claimUse.lockedAt();
            if (claimUse.orderExpired()) {
                closePending(order, now, CLOSED);
                return toResponse(order);
            }
        } catch (BusinessException exception) {
            if (!"COUPON_NOT_IN_USE_PERIOD".equals(exception.getCode())) throw exception;
            now = LocalDateTime.now();
            closePending(order, now, CLOSED);
            return toResponse(order);
        }
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<CourseOrder>()
                .eq(CourseOrder::getId, orderId)
                .eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getStatus, PENDING)
                .set(CourseOrder::getStatus, PAID)
                .set(CourseOrder::getPaidAmount, ZERO)
                .set(CourseOrder::getUpdatedAt, now)
                .set(CourseOrder::getPaidAt, now));
        if (updated != 1) throw new BusinessException("ORDER_STATUS_INVALID", "订单状态已变化，请刷新后重试");
        order.setStatus(PAID);
        order.setPaidAmount(ZERO);
        order.setUpdatedAt(now);
        order.setPaidAt(now);
        return toResponse(order);
    }

    @Transactional
    public CourseOrderResponse cancel(Long userId, Long orderId) {
        CourseOrder order = requireOwnedForUpdate(userId, orderId);
        if (CANCELLED.equals(order.getStatus()) || CLOSED.equals(order.getStatus())) return toResponse(order);
        if (!PENDING.equals(order.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅待支付订单可以取消");
        }
        closePending(order, LocalDateTime.now(), CANCELLED);
        return toResponse(order);
    }

    @Transactional
    public CourseOrderResponse refund(Long userId, Long orderId) {
        CourseOrder order = requireOwnedForUpdate(userId, orderId);
        if (REFUNDED.equals(order.getStatus())) return toResponse(order);
        if (!PAID.equals(order.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅已支付订单可以退款");
        }
        if (!LOCAL_DEMO.equals(effectivePaymentMode())
                || !(LOCAL_DEMO.equals(order.getPaymentMode()) || "LEGACY_DEMO".equals(order.getPaymentMode()))) {
            throw new BusinessException("REFUND_UNAVAILABLE", "当前环境未开放退款");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<CourseOrder>()
                .eq(CourseOrder::getId, orderId).eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getStatus, PAID)
                .set(CourseOrder::getStatus, REFUNDED)
                .set(CourseOrder::getUpdatedAt, now)
                .set(CourseOrder::getRefundedAt, now));
        if (updated != 1) throw new BusinessException("ORDER_STATUS_INVALID", "订单状态已变化，请刷新后重试");
        releaseUsedClaim(order, now);
        order.setStatus(REFUNDED);
        order.setUpdatedAt(now);
        order.setRefundedAt(now);
        return toResponse(order);
    }

    @Scheduled(fixedDelayString = "${learnhub.payment.close-expired-delay:60000}")
    @Transactional
    public void closeExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        orderMapper.selectExpiredForUpdate(now, 100)
                .forEach(order -> closePending(order, now, CLOSED));
    }

    private void preparePending(CourseOrder order, Course course, BigDecimal originalAmount, LocalDateTime now) {
        order.setOrderNo("LH" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        order.setCourseTitle(course.getTitle());
        order.setOriginalAmount(originalAmount);
        order.setCouponClaimId(null);
        order.setDiscountAmount(ZERO);
        order.setPayableAmount(originalAmount);
        order.setPaidAmount(null);
        order.setPaymentMode(effectivePaymentMode());
        order.setStatus(PENDING);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setPaidAt(null);
        order.setExpiresAt(now.plus(pendingTimeout));
        order.setCancelledAt(null);
        order.setRefundedAt(null);
    }

    private void applyCoupon(CourseOrder order, Long userId, Long claimId,
                             BigDecimal originalAmount) {
        CouponClaimEntity claim = claimMapper.selectOwnedForUpdate(claimId, userId);
        LocalDateTime claimLockedAt = LocalDateTime.now();
        if (claim == null) {
            throw new BusinessException("COUPON_CLAIM_NOT_FOUND", "优惠券不存在或不属于当前用户");
        }
        if (!"AVAILABLE".equals(claim.getStatus())) {
            throw new BusinessException("COUPON_NOT_AVAILABLE", "优惠券当前不可用");
        }
        CouponEntity coupon = couponMapper.selectForUpdate(claim.getCouponId());
        if (coupon == null) throw new BusinessException("COUPON_NOT_FOUND", "优惠券不存在");
        if (claimLockedAt.isBefore(coupon.getUseStartAt()) || !claimLockedAt.isBefore(coupon.getUseEndAt())) {
            throw new BusinessException("COUPON_NOT_IN_USE_PERIOD", "优惠券不在可使用时间内");
        }
        if (originalAmount.compareTo(money(coupon.getThresholdAmount())) < 0) {
            throw new BusinessException("COUPON_THRESHOLD_NOT_MET", "订单金额未达到优惠券使用门槛");
        }
        BigDecimal discount = money(coupon.getDiscountAmount()).min(originalAmount);
        int updated = claimMapper.update(null, new LambdaUpdateWrapper<CouponClaimEntity>()
                .eq(CouponClaimEntity::getId, claimId)
                .eq(CouponClaimEntity::getUserId, userId)
                .eq(CouponClaimEntity::getStatus, "AVAILABLE")
                .set(CouponClaimEntity::getStatus, "RESERVED")
                .set(CouponClaimEntity::getReservedOrderId, order.getId())
                .set(CouponClaimEntity::getReservedAt, claimLockedAt)
                .set(CouponClaimEntity::getUpdatedAt, claimLockedAt));
        if (updated != 1) {
            throw new BusinessException("COUPON_NOT_AVAILABLE", "优惠券状态已变化，请刷新后重试");
        }
        order.setCouponClaimId(claimId);
        order.setDiscountAmount(discount);
        order.setPayableAmount(originalAmount.subtract(discount));
        order.setExpiresAt(claimLockedAt.plus(pendingTimeout));
        order.setUpdatedAt(claimLockedAt);
    }

    private ClaimUseOutcome useReservedClaim(CourseOrder order, Long userId) {
        if (order.getCouponClaimId() == null) {
            LocalDateTime checkedAt = LocalDateTime.now();
            return new ClaimUseOutcome(isExpiredPending(order, checkedAt), checkedAt);
        }
        CouponClaimEntity claim = claimMapper.selectOwnedForUpdate(order.getCouponClaimId(), userId);
        LocalDateTime claimLockedAt = LocalDateTime.now();
        if (claim == null || !"RESERVED".equals(claim.getStatus())
                || !order.getId().equals(claim.getReservedOrderId())) {
            throw new BusinessException("COUPON_RESERVATION_INVALID", "订单优惠券占用状态无效");
        }
        if (isExpiredPending(order, claimLockedAt)) {
            return new ClaimUseOutcome(true, claimLockedAt);
        }
        CouponEntity coupon = couponMapper.selectForUpdate(claim.getCouponId());
        if (coupon == null || claimLockedAt.isBefore(coupon.getUseStartAt())
                || !claimLockedAt.isBefore(coupon.getUseEndAt())) {
            throw new BusinessException("COUPON_NOT_IN_USE_PERIOD", "优惠券已过期，请取消订单后重新下单");
        }
        int updated = claimMapper.update(null, new LambdaUpdateWrapper<CouponClaimEntity>()
                .eq(CouponClaimEntity::getId, claim.getId())
                .eq(CouponClaimEntity::getStatus, "RESERVED")
                .eq(CouponClaimEntity::getReservedOrderId, order.getId())
                .set(CouponClaimEntity::getStatus, "USED")
                .set(CouponClaimEntity::getReservedOrderId, null)
                .set(CouponClaimEntity::getUsedOrderId, order.getId())
                .set(CouponClaimEntity::getUsedAt, claimLockedAt)
                .set(CouponClaimEntity::getUpdatedAt, claimLockedAt));
        if (updated != 1) {
            throw new BusinessException("COUPON_RESERVATION_INVALID", "优惠券状态已变化，请刷新后重试");
        }
        return new ClaimUseOutcome(false, claimLockedAt);
    }

    private void releaseReservedClaim(CourseOrder order, LocalDateTime now) {
        if (order.getCouponClaimId() == null) return;
        int updated = claimMapper.update(null, new LambdaUpdateWrapper<CouponClaimEntity>()
                .eq(CouponClaimEntity::getId, order.getCouponClaimId())
                .eq(CouponClaimEntity::getStatus, "RESERVED")
                .eq(CouponClaimEntity::getReservedOrderId, order.getId())
                .set(CouponClaimEntity::getStatus, "AVAILABLE")
                .set(CouponClaimEntity::getReservedOrderId, null)
                .set(CouponClaimEntity::getReservedAt, null)
                .set(CouponClaimEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new BusinessException("COUPON_RESERVATION_INVALID", "订单优惠券占用状态无效");
        }
    }

    private void releaseUsedClaim(CourseOrder order, LocalDateTime now) {
        if (order.getCouponClaimId() == null) return;
        int updated = claimMapper.update(null, new LambdaUpdateWrapper<CouponClaimEntity>()
                .eq(CouponClaimEntity::getId, order.getCouponClaimId())
                .eq(CouponClaimEntity::getStatus, "USED")
                .eq(CouponClaimEntity::getUsedOrderId, order.getId())
                .set(CouponClaimEntity::getStatus, "AVAILABLE")
                .set(CouponClaimEntity::getUsedOrderId, null)
                .set(CouponClaimEntity::getUsedAt, null)
                .set(CouponClaimEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new BusinessException("COUPON_USE_INVALID", "订单优惠券核销状态无效");
        }
    }

    private void closePending(CourseOrder order, LocalDateTime now, String targetStatus) {
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<CourseOrder>()
                .eq(CourseOrder::getId, order.getId())
                .eq(CourseOrder::getStatus, PENDING)
                .set(CourseOrder::getStatus, targetStatus)
                .set(CourseOrder::getUpdatedAt, now)
                .set(CourseOrder::getCancelledAt, now));
        if (updated == 1) {
            releaseReservedClaim(order, now);
            order.setStatus(targetStatus);
            order.setUpdatedAt(now);
            order.setCancelledAt(now);
        }
    }

    private CourseOrder requireOwnedForUpdate(Long userId, Long orderId) {
        CourseOrder order = orderMapper.selectOwnedForUpdate(orderId, userId);
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        return order;
    }

    private boolean isExpiredPending(CourseOrder order, LocalDateTime now) {
        return PENDING.equals(order.getStatus()) && order.getExpiresAt() != null
                && !now.isBefore(order.getExpiresAt());
    }

    private String effectivePaymentMode() {
        boolean production = environment != null
                && environment.acceptsProfiles(Profiles.of("prod", "production"));
        return LOCAL_DEMO.equals(configuredPaymentMode) && !production ? LOCAL_DEMO : DISABLED;
    }

    private BigDecimal money(BigDecimal amount) {
        return amount == null ? ZERO : amount.max(ZERO);
    }

    private CourseOrderResponse toResponse(CourseOrder order) {
        return new CourseOrderResponse(String.valueOf(order.getId()), order.getOrderNo(),
                String.valueOf(order.getCourseId()), order.getCourseTitle(), order.getOriginalAmount(),
                order.getCouponClaimId(), money(order.getDiscountAmount()), money(order.getPayableAmount()),
                order.getPaidAmount(), order.getPaymentMode(), order.getStatus(), order.getCreatedAt(),
                order.getPaidAt(), order.getExpiresAt(), order.getCancelledAt(), order.getRefundedAt());
    }

    private record ClaimUseOutcome(boolean orderExpired, LocalDateTime lockedAt) {}
}
