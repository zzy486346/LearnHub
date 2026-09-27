package com.learnhub.interaction.qa;

import java.time.Instant;
import java.util.List;

public record Question(Long id, Long userId, Long courseId, String title, String content,
                       Instant createdAt, List<Answer> answers) {
}
