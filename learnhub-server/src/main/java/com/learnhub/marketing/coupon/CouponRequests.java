package com.learnhub.marketing.coupon;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public final class CouponRequests {
    private CouponRequests() {}

    public record Create(@NotBlank String name, @Positive int stock, Instant startAt,
                         @NotNull @Future Instant endAt, boolean seckill) {}
}
