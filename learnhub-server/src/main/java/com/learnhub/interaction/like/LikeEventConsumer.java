package com.learnhub.interaction.like;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "learnhub.rabbit.enabled", havingValue = "true")
public class LikeEventConsumer {
    private final LikeEventAggregationService aggregationService;

    public LikeEventConsumer(LikeEventAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @RabbitListener(queues = LikeService.QUEUE, containerFactory = "likeBatchListenerContainerFactory")
    public void consume(List<LikeEvent> events) {
        aggregationService.aggregate(events);
    }
}
