package com.learnhub.interaction.like;

public record LikeRelationState(Long userId, LikeTargetType targetType, Long targetId,
                                boolean active, long sequence) {}
