package com.learnhub.interaction.like;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class LikeMessagingConfiguration {
    @Bean
    DirectExchange interactionExchange() { return new DirectExchange(LikeService.EXCHANGE, true, false); }

    @Bean
    Queue likeEventQueue() { return new Queue("learnhub.like.events", true); }

    @Bean
    Binding likeEventBinding(DirectExchange interactionExchange, Queue likeEventQueue) {
        return BindingBuilder.bind(likeEventQueue).to(interactionExchange).with(LikeService.ROUTING_KEY);
    }
}
