package com.learnhub.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProgressUpdateRequest(
        @NotNull Long lessonId,
        @NotNull @Min(0) Integer positionSeconds,
        @NotNull Boolean completed) {}
