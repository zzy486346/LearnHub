package com.learnhub.interaction.qa;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record Answer(@JsonSerialize(using = ToStringSerializer.class) Long id,
                     @JsonSerialize(using = ToStringSerializer.class) Long questionId,
                     @JsonSerialize(using = ToStringSerializer.class) Long userId,
                     String nickname, String content,
                     boolean accepted, long likeCount, LocalDateTime createdAt) {
}
