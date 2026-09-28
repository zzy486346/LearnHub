package com.learnhub.interaction.qa;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public record Question(@JsonSerialize(using = ToStringSerializer.class) Long id,
                       @JsonSerialize(using = ToStringSerializer.class) Long userId,
                       String nickname,
                       @JsonSerialize(using = ToStringSerializer.class) Long courseId,
                       String title, String content,
                       String status, long likeCount, int answerCount, LocalDateTime createdAt) {
}
