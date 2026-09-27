package com.learnhub.marketing.coupon;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class CouponMessagingConfiguration {
    @Bean
    DirectExchange marketingExchange() { return new DirectExchange(CouponService.EXCHANGE, true, false); }

    @Bean
    Queue couponSeckillQueue() { return new Queue("learnhub.coupon.seckill", true); }

    @Bean
    Binding couponSeckillBinding(DirectExchange marketingExchange, Queue couponSeckillQueue) {
        return BindingBuilder.bind(couponSeckillQueue).to(marketingExchange).with(CouponService.ROUTING_KEY);
    }
}
