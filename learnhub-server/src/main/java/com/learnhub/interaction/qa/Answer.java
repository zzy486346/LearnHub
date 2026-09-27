package com.learnhub.interaction.qa;

import java.time.Instant;

public record Answer(Long id, Long userId, String content, Instant createdAt) {
}
