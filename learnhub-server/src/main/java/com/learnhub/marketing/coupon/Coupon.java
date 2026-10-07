package com.learnhub.marketing.coupon;

import java.time.Instant;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record Coupon(@JsonSerialize(using = ToStringSerializer.class) Long id, String name,
                     int stock, Instant startAt, Instant endAt, boolean seckill,
                     BigDecimal discountAmount, BigDecimal thresholdAmount,
                     Instant useStartAt, Instant useEndAt, String status) {
    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }
}
