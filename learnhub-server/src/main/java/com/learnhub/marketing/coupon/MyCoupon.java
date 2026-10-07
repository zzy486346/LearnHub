package com.learnhub.marketing.coupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record MyCoupon(@JsonSerialize(using = ToStringSerializer.class) Long couponId,
                       String name, String type, BigDecimal discountAmount, BigDecimal thresholdAmount,
                       LocalDateTime useStartAt, LocalDateTime useEndAt, LocalDateTime claimedAt,
                       String status) {}
