package com.learnhub.interaction.qa;

import jakarta.validation.constraints.NotBlank;

public final class QaRequests {
    private QaRequests() {}

    public record CreateQuestion(Long courseId, @NotBlank String title, @NotBlank String content) {}
    public record CreateAnswer(@NotBlank String content) {}
}
