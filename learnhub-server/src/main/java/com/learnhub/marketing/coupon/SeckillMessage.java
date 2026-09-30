package com.learnhub.marketing.coupon;

import java.io.Serializable;
import java.time.LocalDateTime;

public record SeckillMessage(String messageId, String requestId, Long couponId, Long userId,
                             LocalDateTime reservedAt, int version) implements Serializable {
}
