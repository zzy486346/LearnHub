package com.learnhub.interaction.like;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class LikeMessagingConfiguration {
    @Bean
    DirectExchange interactionExchange() { return new DirectExchange(LikeService.EXCHANGE, true, false); }

    @Bean
    DirectExchange interactionDeadLetterExchange() {
        return new DirectExchange(LikeService.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue likeEventQueue() {
        return QueueBuilder.durable(LikeService.QUEUE)
                .deadLetterExchange(LikeService.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(LikeService.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue likeEventDeadLetterQueue() {
        return QueueBuilder.durable(LikeService.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding likeEventBinding(DirectExchange interactionExchange, Queue likeEventQueue) {
        return BindingBuilder.bind(likeEventQueue).to(interactionExchange).with(LikeService.ROUTING_KEY);
    }

    @Bean
    Binding likeEventDeadLetterBinding(DirectExchange interactionDeadLetterExchange,
                                       Queue likeEventDeadLetterQueue) {
        return BindingBuilder.bind(likeEventDeadLetterQueue)
                .to(interactionDeadLetterExchange)
                .with(LikeService.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    SimpleRabbitListenerContainerFactory likeBatchListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            caching.setPublisherReturns(true);
        }
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(100);
        factory.setReceiveTimeout(1000L);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
