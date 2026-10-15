package com.learnhub.assistant.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ImportDocumentRequest(
        @NotBlank @Size(max = 191) String sourceKey,
        @NotBlank @Size(max = 160) String title,
        @NotNull @Positive Long courseId,
        @NotNull @Positive Long lessonId,
        @NotEmpty @Size(max = 200) List<@NotNull @Valid Scene> scenes) {

    public record Scene(
            @Size(max = 255) String heading,
            @NotBlank @Size(max = 20000) String content) {
    }
}
