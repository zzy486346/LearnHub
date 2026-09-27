package com.learnhub.marketing.coupon;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CouponService {
    public static final String EXCHANGE = "learnhub.marketing";
    public static final String ROUTING_KEY = "coupon.seckill";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/coupon_seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final RedissonClient redisson;
    private final boolean rabbitEnabled;
    private final AtomicLong couponIds = new AtomicLong();
    private final Map<Long, Coupon> coupons = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> fallbackStock = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> fallbackClaims = new ConcurrentHashMap<>();
    private final Map<String, CouponClaim> claims = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public CouponService(ObjectProvider<StringRedisTemplate> redisProvider,
                         ObjectProvider<RabbitTemplate> rabbitProvider,
                         ObjectProvider<RedissonClient> redissonProvider,
                         @Value("${learnhub.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.redis = redisProvider.getIfAvailable();
        this.rabbit = rabbitProvider.getIfAvailable();
        this.redisson = redissonProvider.getIfAvailable();
        this.rabbitEnabled = rabbitEnabled;
    }

    public Coupon create(CouponRequests.Create request) {
        long id = couponIds.incrementAndGet();
        Instant start = request.startAt() == null ? Instant.now() : request.startAt();
        Coupon coupon = new Coupon(id, request.name(), request.stock(), start, request.endAt(), request.seckill());
        coupons.put(id, coupon);
        fallbackStock.put(id, new AtomicInteger(request.stock()));
        fallbackClaims.put(id, ConcurrentHashMap.newKeySet());
        if (request.seckill() && redis != null) {
            try {
                redis.opsForValue().set(stockKey(id), Integer.toString(request.stock()));
            } catch (RuntimeException ignored) {
                // Local fallback remains usable for a single-node development environment.
            }
        }
        return coupon;
    }

    public List<Coupon> list() {
        return coupons.values().stream().toList();
    }

    public CouponClaim claim(Long couponId, Long userId) {
        Coupon coupon = requireActive(couponId, false);
        synchronized (fallbackStock.computeIfAbsent(couponId, ignored -> new AtomicInteger(coupon.stock()))) {
            ensureNotClaimed(couponId, userId);
            AtomicInteger stock = fallbackStock.get(couponId);
            if (stock.get() <= 0) throw new IllegalStateException("Coupon sold out");
            stock.decrementAndGet();
            fallbackClaims.computeIfAbsent(couponId, ignored -> ConcurrentHashMap.newKeySet()).add(userId);
            return save(couponId, userId, CouponClaim.Status.CONFIRMED);
        }
    }

    public CouponClaim seckill(Long couponId, Long userId) {
        requireActive(couponId, true);
        boolean reserved = reserveInRedis(couponId, userId);
        if (!reserved) reserveLocally(couponId, userId);
        CouponClaim claim = save(couponId, userId, CouponClaim.Status.RESERVED);
        if (rabbitEnabled && rabbit != null) {
            rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, new SeckillMessage(couponId, userId, claim.claimedAt()));
        } else {
            confirm(couponId, userId);
            claim = claims.get(claimKey(couponId, userId));
        }
        return claim;
    }

    public CouponClaim confirm(Long couponId, Long userId) {
        String lockName = "learnhub:lock:coupon:" + couponId + ":" + userId;
        if (redisson != null) {
            RLock lock = redisson.getLock(lockName);
            lock.lock();
            try {
                return save(couponId, userId, CouponClaim.Status.CONFIRMED);
            } finally {
                lock.unlock();
            }
        }
        ReentrantLock lock = localLocks.computeIfAbsent(couponId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return save(couponId, userId, CouponClaim.Status.CONFIRMED);
        } finally {
            lock.unlock();
        }
    }

    public CouponClaim status(Long couponId, Long userId) {
        return claims.get(claimKey(couponId, userId));
    }

    private boolean reserveInRedis(Long couponId, Long userId) {
        if (redis == null) return false;
        try {
            Long code = redis.execute(SECKILL_SCRIPT, List.of(stockKey(couponId), usersKey(couponId)), userId.toString());
            if (code == null || code == 3) return false;
            if (code == 1) throw new IllegalStateException("Coupon sold out");
            if (code == 2) throw new IllegalStateException("Coupon already claimed");
            return true;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void reserveLocally(Long couponId, Long userId) {
        Coupon coupon = coupons.get(couponId);
        AtomicInteger stock = fallbackStock.computeIfAbsent(couponId, ignored -> new AtomicInteger(coupon.stock()));
        Set<Long> users = fallbackClaims.computeIfAbsent(couponId, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (stock) {
            if (!users.add(userId)) throw new IllegalStateException("Coupon already claimed");
            if (stock.get() <= 0) {
                users.remove(userId);
                throw new IllegalStateException("Coupon sold out");
            }
            stock.decrementAndGet();
        }
    }

    private Coupon requireActive(Long couponId, boolean seckill) {
        Coupon coupon = coupons.get(couponId);
        if (coupon == null) throw new IllegalArgumentException("Coupon not found: " + couponId);
        if (coupon.seckill() != seckill) throw new IllegalStateException("Coupon type mismatch");
        if (!coupon.activeAt(Instant.now())) throw new IllegalStateException("Coupon is not active");
        return coupon;
    }

    private void ensureNotClaimed(Long couponId, Long userId) {
        if (fallbackClaims.getOrDefault(couponId, Set.of()).contains(userId)) {
            throw new IllegalStateException("Coupon already claimed");
        }
    }

    private CouponClaim save(Long couponId, Long userId, CouponClaim.Status status) {
        return claims.compute(claimKey(couponId, userId), (key, existing) -> {
            if (existing != null && existing.status() == CouponClaim.Status.CONFIRMED) return existing;
            return new CouponClaim(couponId, userId, existing == null ? Instant.now() : existing.claimedAt(), status);
        });
    }

    private String claimKey(Long couponId, Long userId) { return couponId + ":" + userId; }
    private String stockKey(Long couponId) { return "learnhub:coupon:" + couponId + ":stock"; }
    private String usersKey(Long couponId) { return "learnhub:coupon:" + couponId + ":users"; }
}
