package com.learnhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.interaction.like.LikeEvent;
import com.learnhub.interaction.like.LikeTargetType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

class RabbitMessageConfigTest {
    @Test
    void serializesAndDeserializesLikeEventAsJson() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        MessageConverter converter = new RabbitMessageConfig().rabbitMessageConverter(objectMapper);
        LikeEvent event = new LikeEvent("event-1", 10L, LikeTargetType.COURSE, 1002L,
                true, 1L, Instant.now(), 1);

        Message message = converter.toMessage(event, new MessageProperties());
        Object restored = converter.fromMessage(message);

        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(restored).isEqualTo(event);
    }
}
