package com.learnhub.marketing.coupon;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;

public final class CouponRequests {
    private CouponRequests() {}

    public record Create(@NotBlank String name, @Positive int stock, Instant startAt,
                         @NotNull @Future Instant endAt, boolean seckill,
                         @NotNull @DecimalMin(value = "0.01") BigDecimal discountAmount,
                         @NotNull @DecimalMin(value = "0.00") BigDecimal thresholdAmount,
                         Instant useStartAt, Instant useEndAt) {
        public Create(String name, int stock, Instant startAt, Instant endAt, boolean seckill) {
            this(name, stock, startAt, endAt, seckill, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }
    }
}
