package com.learnhub.marketing.coupon;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class SeckillConsumer {
    private final CouponService service;

    public SeckillConsumer(CouponService service) { this.service = service; }

    @RabbitListener(queues = "learnhub.coupon.seckill")
    public void consume(SeckillMessage message) {
        service.confirm(message.couponId(), message.userId());
    }
}
