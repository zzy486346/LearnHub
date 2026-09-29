package com.learnhub.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class MediaRequests {
    private MediaRequests() {}

    public record PresignUpload(
            @NotBlank @Size(max = 255) String originalFilename,
            @NotBlank @Size(max = 128) String contentType,
            @Positive long size,
            @NotBlank @Size(max = 32) String bizType,
            @Positive Long bizId) {}
}
