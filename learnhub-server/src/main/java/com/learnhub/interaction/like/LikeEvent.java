package com.learnhub.interaction.like;

import java.time.Instant;
import java.io.Serializable;

public record LikeEvent(String eventId, Long userId, LikeTargetType targetType, Long targetId,
                        boolean liked, long sequence, Instant occurredAt, int version)
        implements Serializable {
}
