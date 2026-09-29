package com.learnhub.storage;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaAssetController {
    private final MediaAssetService service;

    public MediaAssetController(MediaAssetService service) {
        this.service = service;
    }

    @PostMapping("/uploads/presign")
    public ApiResponse<MediaResponses.UploadTicket> presign(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @Valid @RequestBody MediaRequests.PresignUpload request) {
        return ApiResponse.success(service.createUpload(principal.userId(), request));
    }

    @PostMapping("/uploads/{assetId}/complete")
    public ApiResponse<MediaResponses.Asset> complete(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @PathVariable Long assetId) {
        return ApiResponse.success(service.completeUpload(principal.userId(), assetId));
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaResponses.Asset> upload(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam String bizType,
            @RequestParam(required = false) Long bizId) {
        return ApiResponse.success(service.upload(principal.userId(), file, bizType, bizId));
    }

    @GetMapping("/{assetId}")
    public ApiResponse<MediaResponses.Asset> detail(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @PathVariable Long assetId) {
        return ApiResponse.success(service.detail(principal.userId(), assetId));
    }

    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @PathVariable Long assetId) {
        service.delete(principal.userId(), assetId);
        return ApiResponse.success(null);
    }
}
