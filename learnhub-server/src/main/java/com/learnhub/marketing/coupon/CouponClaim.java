package com.learnhub.marketing.coupon;

import java.time.Instant;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record CouponClaim(@JsonSerialize(using = ToStringSerializer.class) Long couponId,
                          @JsonSerialize(using = ToStringSerializer.class) Long userId,
                          Instant claimedAt, Status status) {
    public enum Status { RESERVED, CONFIRMED, REJECTED }
}
