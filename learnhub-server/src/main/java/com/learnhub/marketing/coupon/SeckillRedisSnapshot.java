package com.learnhub.marketing.coupon;

import java.util.List;

public record SeckillRedisSnapshot(CouponEntity coupon, List<SeckillOrderEntity> reservations) {}
