package com.learnhub.marketing.coupon;

import java.time.Instant;

public record Coupon(Long id, String name, int stock, Instant startAt, Instant endAt, boolean seckill) {
    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }
}
