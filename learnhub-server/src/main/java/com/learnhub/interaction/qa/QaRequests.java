package com.learnhub.interaction.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class QaRequests {
    private QaRequests() {}

    public record CreateQuestion(@NotNull Long courseId, @NotBlank String title, @NotBlank String content) {}
    public record CreateAnswer(@NotBlank String content) {}
}
