package com.learnhub.marketing.coupon;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class CouponMessagingConfiguration {
    @Bean
    DirectExchange marketingExchange() { return new DirectExchange(CouponService.EXCHANGE, true, false); }

    @Bean
    DirectExchange marketingDeadLetterExchange() {
        return new DirectExchange(CouponService.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue couponSeckillQueue() {
        return QueueBuilder.durable(CouponService.QUEUE)
                .deadLetterExchange(CouponService.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(CouponService.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue couponSeckillDeadLetterQueue() {
        return QueueBuilder.durable(CouponService.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding couponSeckillBinding(DirectExchange marketingExchange, Queue couponSeckillQueue) {
        return BindingBuilder.bind(couponSeckillQueue).to(marketingExchange).with(CouponService.ROUTING_KEY);
    }

    @Bean
    Binding couponSeckillDeadLetterBinding(DirectExchange marketingDeadLetterExchange,
                                            Queue couponSeckillDeadLetterQueue) {
        return BindingBuilder.bind(couponSeckillDeadLetterQueue)
                .to(marketingDeadLetterExchange)
                .with(CouponService.DEAD_LETTER_ROUTING_KEY);
    }
}
