package com.learnhub.marketing.coupon;

import java.time.Instant;

public record CouponClaim(Long couponId, Long userId, Instant claimedAt, Status status) {
    public enum Status { RESERVED, CONFIRMED, REJECTED }
}
