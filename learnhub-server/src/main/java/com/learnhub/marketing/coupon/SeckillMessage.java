package com.learnhub.marketing.coupon;

import java.time.Instant;
import java.io.Serializable;

public record SeckillMessage(Long couponId, Long userId, Instant reservedAt) implements Serializable {
}
