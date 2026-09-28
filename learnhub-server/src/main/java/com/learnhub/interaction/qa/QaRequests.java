package com.learnhub.interaction.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class QaRequests {
    private QaRequests() {}

    public record CreateQuestion(Long courseId,
                                 @NotBlank @Size(max = 200) String title,
                                 @NotBlank @Size(max = 10000) String content) {}
    public record CreateAnswer(@NotBlank @Size(max = 10000) String content) {}
}
