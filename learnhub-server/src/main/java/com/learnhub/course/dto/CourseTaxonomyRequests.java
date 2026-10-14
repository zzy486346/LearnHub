package com.learnhub.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CourseTaxonomyRequests {
    private CourseTaxonomyRequests() {}

    public record CreateCategory(
            @NotBlank @Size(max = 64) String name,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status
    ) {}

    public record UpdateCategory(
            @NotBlank @Size(max = 64) String name,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status
    ) {}

    public record CreateTag(@NotBlank @Size(max = 64) String name) {}

    public record UpdateTag(@NotBlank @Size(max = 64) String name) {}
}
