package com.learnhub.interaction.like;

public record LikeCountDelta(LikeTargetType targetType, Long targetId, long delta) {}
