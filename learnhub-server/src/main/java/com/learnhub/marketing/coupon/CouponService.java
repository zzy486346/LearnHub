package com.learnhub.marketing.coupon;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.learnhub.common.exception.BusinessException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class CouponService {
    public static final String EXCHANGE = "learnhub.marketing";
    public static final String ROUTING_KEY = "coupon.seckill.v2";
    public static final String QUEUE = "learnhub.coupon.seckill.v2";
    public static final String DEAD_LETTER_EXCHANGE = "learnhub.marketing.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "coupon.seckill.v2.dead";
    public static final String DEAD_LETTER_QUEUE = "learnhub.coupon.seckill.v2.dlq";
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("lua/coupon_seckill.lua");
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = script("lua/coupon_seckill_compensate.lua");
    private static final DefaultRedisScript<Long> REBUILD_SCRIPT = script("lua/coupon_seckill_rebuild.lua");
    private static final DefaultRedisScript<Long> FINALIZE_SCRIPT = script("lua/coupon_seckill_finalize.lua");

    private final SeckillPersistenceService persistence;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final RedissonClient redisson;
    private final boolean rabbitEnabled;
    private final int maxPublishAttempts;
    private final Duration consumerConfirmationTimeout;
    private final Duration reservationOrphanTimeout;

    public CouponService(SeckillPersistenceService persistence,
                         ObjectProvider<StringRedisTemplate> redisProvider,
                         ObjectProvider<RabbitTemplate> rabbitProvider,
                         ObjectProvider<RedissonClient> redissonProvider,
                         @Value("${learnhub.rabbit.enabled:false}") boolean rabbitEnabled,
                         @Value("${learnhub.seckill.max-publish-attempts:5}") int maxPublishAttempts,
                         @Value("${learnhub.seckill.consumer-confirmation-timeout:PT1M}") Duration consumerConfirmationTimeout,
                         @Value("${learnhub.seckill.reservation-orphan-timeout:PT1M}") Duration reservationOrphanTimeout) {
        this.persistence = persistence;
        this.redis = redisProvider.getIfAvailable();
        this.rabbit = rabbitProvider.getIfAvailable();
        this.redisson = redissonProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
        this.maxPublishAttempts = maxPublishAttempts;
        this.consumerConfirmationTimeout = consumerConfirmationTimeout;
        this.reservationOrphanTimeout = reservationOrphanTimeout;
    }

    public Coupon create(CouponRequests.Create request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = local(request.startAt() == null ? Instant.now() : request.startAt());
        LocalDateTime end = local(request.endAt());
        CouponEntity entity = new CouponEntity();
        entity.setId(IdWorker.getId());
        entity.setName(request.name());
        entity.setType(request.seckill() ? "SECKILL" : "NORMAL");
        entity.setThresholdAmount(BigDecimal.ZERO);
        entity.setDiscountAmount(BigDecimal.ZERO);
        entity.setTotalStock(request.stock());
        entity.setAvailableStock(request.stock());
        entity.setClaimStartAt(start);
        entity.setClaimEndAt(end);
        entity.setUseStartAt(start);
        entity.setUseEndAt(end);
        entity.setPerUserLimit(1);
        entity.setStatus("ACTIVE");
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (request.seckill()) initializeRedisStock(entity.getId(), request.stock());
        try {
            persistence.createCoupon(entity);
        } catch (RuntimeException ex) {
            if (request.seckill() && redis != null) {
                redis.delete(List.of(stockKey(entity.getId()), usersKey(entity.getId())));
            }
            throw ex;
        }
        return toCoupon(entity);
    }

    public List<Coupon> list() {
        return persistence.listCoupons().stream().map(this::toCoupon).toList();
    }

    public List<MyCoupon> mine(Long userId) {
        return persistence.listMyCoupons(userId);
    }

    public CouponClaim claim(Long couponId, Long userId) {
        requireActive(couponId, false);
        return toClaim(persistence.claimNormally(couponId, userId, LocalDateTime.now()));
    }

    public CouponClaim seckill(Long couponId, Long userId) {
        requireActive(couponId, true);
        String requestId = UUID.randomUUID().toString();
        reserveInRedis(requestId, couponId, userId);
        LocalDateTime reservedAt = LocalDateTime.now();
        try {
            persistence.createPendingOrder(requestId, couponId, userId, reservedAt);
        } catch (RuntimeException ex) {
            compensateRedis(requestId, couponId, userId);
            throw ex;
        }
        SeckillMessage message = new SeckillMessage(requestId, requestId, couponId, userId, reservedAt, 1);
        if (!finalizeReservation(requestId, couponId, userId)) {
            failAndCompensate(message, "Redis reservation expired before durable order confirmation");
            throw new IllegalStateException("Seckill reservation expired; please retry");
        }
        publish(message);
        return toClaim(persistence.findOrder(requestId));
    }

    public void publish(SeckillMessage message) {
        if (!rabbitEnabled) {
            try {
                confirm(message);
            } catch (RuntimeException ex) {
                failAndCompensate(message, ex.getMessage());
                throw ex;
            }
            return;
        }
        if (persistence.markPublishing(message.requestId(), LocalDateTime.now().plusSeconds(10)) != 1) return;
        SeckillOrderEntity publishing = persistence.findOrder(message.requestId());
        int publishAttempt = publishing.getPublishAttempts();
        if (rabbit == null) {
            persistence.markPublishFailed(message.requestId(), publishAttempt, "RabbitTemplate unavailable");
            return;
        }
        CorrelationData correlation = new CorrelationData(message.messageId() + ":" + publishAttempt);
        correlation.getFuture().whenComplete((confirm, error) -> {
            if (error != null) {
                persistence.markPublishFailed(message.requestId(), publishAttempt, error.getMessage());
            } else if (confirm != null && confirm.isAck() && correlation.getReturned() == null) {
                persistence.markPublished(message.requestId(), publishAttempt,
                        LocalDateTime.now().plus(consumerConfirmationTimeout));
            } else {
                String reason = correlation.getReturned() != null
                        ? "Message returned: " + correlation.getReturned().getReplyText()
                        : confirm == null ? "Missing publisher confirm" : confirm.getReason();
                persistence.markPublishFailed(message.requestId(), publishAttempt, reason);
            }
        });
        try {
            rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, message, correlation);
        } catch (RuntimeException ex) {
            persistence.markPublishFailed(message.requestId(), publishAttempt, ex.getMessage());
        }
    }

    public SeckillOrderEntity confirm(SeckillMessage message) {
        String lockName = "learnhub:lock:coupon:" + message.couponId() + ":" + message.userId();
        RLock lock = redisson == null ? null : redisson.getLock(lockName);
        if (lock == null) return confirmAndCompleteReservation(message);
        lock.lock(15, TimeUnit.SECONDS);
        try {
            return confirmAndCompleteReservation(message);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public void failAndCompensate(SeckillMessage message, String reason) {
        persistence.markFailed(message.requestId(), reason);
        SeckillOrderEntity order = persistence.findOrder(message.requestId());
        if (order != null && "FAILED".equals(order.getStatus()) && !Boolean.TRUE.equals(order.getCompensated())) {
            compensate(order.getRequestId(), order.getCouponId(), order.getUserId());
        }
    }

    public CouponClaim status(Long couponId, Long userId) {
        SeckillOrderEntity order = persistence.findOrder(couponId, userId);
        if (order != null) return toClaim(order);
        CouponClaimEntity claim = persistence.findClaim(couponId, userId);
        return claim == null ? null : toClaim(claim);
    }

    public void reconcile(int limit) {
        for (CouponEntity coupon : persistence.listActiveSeckillCoupons()) {
            reconcileRedisState(coupon.getId());
        }
        for (SeckillOrderEntity order : persistence.findRecoverableOrders(limit)) {
            if ("FAILED".equals(order.getStatus())) {
                compensate(order.getRequestId(), order.getCouponId(), order.getUserId());
            } else if (order.getPublishAttempts() >= maxPublishAttempts) {
                failAndCompensate(toMessage(order), "Publisher confirm retry limit exceeded");
            } else {
                publish(toMessage(order));
            }
        }
    }

    private void initializeRedisStock(Long couponId, int stock) {
        if (redis == null) throw new IllegalStateException("Redis unavailable; seckill coupon cannot be activated");
        redis.opsForValue().set(stockKey(couponId), Integer.toString(stock));
        redis.delete(usersKey(couponId));
    }

    private void reserveInRedis(String requestId, Long couponId, Long userId) {
        if (redis == null) throw new IllegalStateException("Redis unavailable; seckill is temporarily unavailable");
        List<String> keys = List.of(stockKey(couponId), usersKey(couponId), reservationsKey(couponId));
        Long code = redis.execute(SECKILL_SCRIPT, keys, userId.toString(), requestId,
                Long.toString(System.currentTimeMillis()));
        if (code != null && code == 3) {
            rebuildRedisState(couponId);
            code = redis.execute(SECKILL_SCRIPT, keys, userId.toString(), requestId,
                    Long.toString(System.currentTimeMillis()));
        }
        if (code == null || code == 3) throw new IllegalStateException("Seckill stock is not initialized");
        if (code == 1) throw new BusinessException("COUPON_SOLD_OUT", "优惠券已抢光");
        if (code == 2) throw new BusinessException("COUPON_ALREADY_CLAIMED", "已领取或正在处理中，请刷新查看结果");
        if (code != 0) throw new IllegalStateException("Unexpected seckill result: " + code);
    }

    private void compensate(String requestId, Long couponId, Long userId) {
        try {
            if (compensateRedis(requestId, couponId, userId)) persistence.markCompensated(requestId);
        } catch (RuntimeException ex) {
            log.warn("Seckill compensation deferred: requestId={}", requestId, ex);
        }
    }

    private boolean compensateRedis(String requestId, Long couponId, Long userId) {
        if (redis == null) return false;
        Long result = redis.execute(COMPENSATE_SCRIPT,
                List.of(stockKey(couponId), usersKey(couponId), reservationsKey(couponId),
                        compensationKey(couponId, requestId)), userId.toString(), requestId);
        return result != null && (result == 1 || result == 2);
    }

    private boolean finalizeReservation(String requestId, Long couponId, Long userId) {
        if (redis == null) return false;
        Long result = redis.execute(FINALIZE_SCRIPT,
                List.of(reservationsKey(couponId), compensationKey(couponId, requestId)),
                requestId, userId.toString());
        return result != null && result == 1;
    }

    private void rebuildRedisState(Long couponId) {
        if (redis == null || Boolean.TRUE.equals(redis.hasKey(stockKey(couponId)))) return;
        SeckillRedisSnapshot snapshot = persistence.loadRedisSnapshot(couponId);
        List<SeckillOrderEntity> reservations = snapshot.reservations();
        long outstanding = reservations.stream()
                .filter(order -> !"SUCCESS".equals(order.getStatus()))
                .count();
        long expectedStock = Math.max(0, snapshot.coupon().getAvailableStock() - outstanding);
        String[] args = new String[reservations.size() * 3 + 1];
        args[0] = Long.toString(expectedStock);
        for (int i = 0; i < reservations.size(); i++) {
            SeckillOrderEntity order = reservations.get(i);
            int offset = i * 3 + 1;
            args[offset] = order.getUserId().toString();
            args[offset + 1] = order.getRequestId();
            args[offset + 2] = order.getStatus();
        }
        redis.execute(REBUILD_SCRIPT,
                List.of(stockKey(couponId), usersKey(couponId), reservationsKey(couponId)), (Object[]) args);
    }

    private SeckillOrderEntity confirmAndCompleteReservation(SeckillMessage message) {
        SeckillOrderEntity order = persistence.confirm(message);
        if (redis != null && "SUCCESS".equals(order.getStatus())) {
            redis.opsForHash().delete(reservationsKey(order.getCouponId()), order.getRequestId());
        }
        return order;
    }

    private void reconcileRedisState(Long couponId) {
        if (redis == null) return;
        rebuildRedisState(couponId);
        repairRedisMembership(couponId);
        Map<Object, Object> reservations = redis.opsForHash().entries(reservationsKey(couponId));
        long orphanBefore = System.currentTimeMillis() - reservationOrphanTimeout.toMillis();
        for (Map.Entry<Object, Object> entry : reservations.entrySet()) {
            String requestId = entry.getKey().toString();
            String[] value = entry.getValue().toString().split("\\|", 3);
            if (value.length != 3) continue;
            Long userId = Long.valueOf(value[0]);
            long reservedAt = Long.parseLong(value[1]);
            boolean transientReservation = "R".equals(value[2]);
            SeckillOrderEntity order = persistence.findOrder(requestId);
            if (order == null && transientReservation && reservedAt > 0 && reservedAt <= orphanBefore) {
                compensateRedis(requestId, couponId, userId);
            } else if (order != null && "SUCCESS".equals(order.getStatus())) {
                redis.opsForHash().delete(reservationsKey(couponId), requestId);
            } else if (order != null && "FAILED".equals(order.getStatus())
                    && !Boolean.TRUE.equals(order.getCompensated())) {
                compensate(order.getRequestId(), order.getCouponId(), order.getUserId());
            }
        }
    }

    private void repairRedisMembership(Long couponId) {
        if (!Boolean.TRUE.equals(redis.hasKey(stockKey(couponId)))) return;
        SeckillRedisSnapshot snapshot = persistence.loadRedisSnapshot(couponId);
        for (SeckillOrderEntity order : snapshot.reservations()) {
            redis.opsForSet().add(usersKey(couponId), order.getUserId().toString());
            if (!"SUCCESS".equals(order.getStatus())) {
                redis.opsForHash().putIfAbsent(reservationsKey(couponId), order.getRequestId(),
                        order.getUserId() + "|0|P");
            }
        }
    }

    private CouponEntity requireActive(Long couponId, boolean seckill) {
        CouponEntity coupon = persistence.findCoupon(couponId);
        if (coupon == null) throw new BusinessException("COUPON_NOT_FOUND", "优惠券不存在");
        if (("SECKILL".equals(coupon.getType())) != seckill) throw new BusinessException("COUPON_TYPE_MISMATCH", "优惠券领取方式不正确");
        LocalDateTime now = LocalDateTime.now();
        if (!"ACTIVE".equals(coupon.getStatus())) throw new BusinessException("COUPON_DISABLED", "优惠券活动已下架");
        if (now.isBefore(coupon.getClaimStartAt())) throw new BusinessException("COUPON_NOT_STARTED", "优惠券活动尚未开始");
        if (!now.isBefore(coupon.getClaimEndAt())) throw new BusinessException("COUPON_ENDED", "优惠券活动已结束");
        return coupon;
    }

    private Coupon toCoupon(CouponEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        String status = !"ACTIVE".equals(entity.getStatus()) ? "DISABLED"
                : now.isBefore(entity.getClaimStartAt()) ? "UPCOMING"
                : !now.isBefore(entity.getClaimEndAt()) ? "ENDED"
                : entity.getAvailableStock() <= 0 ? "SOLD_OUT" : "ACTIVE";
        return new Coupon(entity.getId(), entity.getName(), entity.getAvailableStock(),
                instant(entity.getClaimStartAt()), instant(entity.getClaimEndAt()), "SECKILL".equals(entity.getType()),
                entity.getDiscountAmount(), entity.getThresholdAmount(),
                instant(entity.getUseStartAt()), instant(entity.getUseEndAt()), status);
    }

    private CouponClaim toClaim(CouponClaimEntity entity) {
        return new CouponClaim(entity.getCouponId(), entity.getUserId(), instant(entity.getClaimedAt()), CouponClaim.Status.CONFIRMED);
    }

    private CouponClaim toClaim(SeckillOrderEntity order) {
        CouponClaim.Status status = switch (order.getStatus()) {
            case "SUCCESS" -> CouponClaim.Status.CONFIRMED;
            case "FAILED" -> CouponClaim.Status.REJECTED;
            default -> CouponClaim.Status.RESERVED;
        };
        return new CouponClaim(order.getCouponId(), order.getUserId(), instant(order.getReservedAt()), status);
    }

    private SeckillMessage toMessage(SeckillOrderEntity order) {
        return new SeckillMessage(order.getRequestId(), order.getRequestId(), order.getCouponId(), order.getUserId(),
                order.getReservedAt(), 1);
    }

    private String stockKey(Long couponId) { return "learnhub:coupon:{" + couponId + "}:stock"; }
    private String usersKey(Long couponId) { return "learnhub:coupon:{" + couponId + "}:users"; }
    private String reservationsKey(Long couponId) { return "learnhub:coupon:{" + couponId + "}:reservations"; }
    private String compensationKey(Long couponId, String requestId) {
        return "learnhub:coupon:{" + couponId + "}:compensated:" + requestId;
    }
    private static LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()); }
    private static Instant instant(LocalDateTime time) { return time.atZone(ZoneId.systemDefault()).toInstant(); }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
