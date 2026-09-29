package com.learnhub.storage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

public final class MediaResponses {
    private MediaResponses() {}

    public record UploadTicket(Long assetId, String objectKey, String method, String uploadUrl,
                               Map<String, String> headers, Instant expiresAt) {}

    public record Asset(Long id, String originalName, String contentType, long size, String status,
                        String bizType, Long bizId, String url, Instant urlExpiresAt,
                        LocalDateTime createdAt) {}
}
