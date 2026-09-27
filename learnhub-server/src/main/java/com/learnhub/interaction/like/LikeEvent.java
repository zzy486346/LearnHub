package com.learnhub.interaction.like;

import java.time.Instant;
import java.io.Serializable;

public record LikeEvent(Long userId, LikeTargetType targetType, Long targetId, boolean liked, Instant occurredAt)
        implements Serializable {
}
