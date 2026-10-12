package com.learnhub.storage;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.storage.config.OssStorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaAssetService {
    private static final String UPLOADING = "UPLOADING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;
    private static final Pattern BIZ_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern EXTENSION = Pattern.compile("[a-zA-Z0-9]{1,10}");
    private static final Set<String> EXTRA_CONTENT_TYPES = Set.of(
            "application/pdf", "application/zip", "application/json", "text/plain");
    private static final Set<String> PUBLIC_BIZ_TYPES = Set.of("AVATAR", "COURSE_COVER");

    private final MediaAssetMapper mapper;
    private final ObjectStorageService storage;
    private final OssStorageProperties properties;

    public MediaAssetService(MediaAssetMapper mapper, ObjectStorageService storage,
                             OssStorageProperties properties) {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
    }

    public MediaResponses.UploadTicket createUpload(Long userId, MediaRequests.PresignUpload request) {
        validate(request.originalFilename(), request.contentType(), request.size(), request.bizType());
        String bizType = request.bizType().trim().toUpperCase(Locale.ROOT);
        Long bizId = validateGenericBusiness(userId, bizType, request.bizId(), request.contentType(), request.size());
        String objectKey = createTemporaryObjectKey(userId, bizType, request.originalFilename());
        PresignedUpload upload = storage.presignUpload(
                objectKey, request.contentType(), properties.getUploadUrlTtl());

        MediaAsset asset = newAsset(userId, objectKey, request.originalFilename(), request.contentType(),
                request.size(), bizType, bizId, UPLOADING);
        mapper.insert(asset);
        return new MediaResponses.UploadTicket(asset.getId(), objectKey, "PUT", upload.url().toString(),
                upload.headers(), upload.expiresAt());
    }

    public MediaResponses.Asset completeUpload(Long userId, Long assetId) {
        MediaAsset asset = requireOwned(userId, assetId);
        if (READY.equals(asset.getStatus())) return toResponse(asset, true);
        if (!UPLOADING.equals(asset.getStatus())) {
            throw new BusinessException("MEDIA_STATE_INVALID", "当前文件状态不能完成上传");
        }
        if (!isTemporaryObjectKey(asset.getObjectKey())) {
            if (asset.getEtag() == null || asset.getEtag().isBlank()) {
                return rejectLegacyUpload(asset);
            }
            return promoteAndComplete(asset, temporaryKeyFor(asset.getObjectKey()), asset.getObjectKey(), asset.getEtag());
        }

        String temporaryKey = asset.getObjectKey();
        StoredObjectMetadata uploaded = storage.metadata(temporaryKey);
        validateUploadedObject(asset, uploaded, temporaryKey);
        String finalKey = finalKeyFor(temporaryKey);
        int claimed = mapper.update(null, new LambdaUpdateWrapper<MediaAsset>()
                .eq(MediaAsset::getId, assetId)
                .eq(MediaAsset::getOwnerId, userId)
                .eq(MediaAsset::getStatus, UPLOADING)
                .eq(MediaAsset::getObjectKey, temporaryKey)
                .set(MediaAsset::getObjectKey, finalKey)
                .set(MediaAsset::getEtag, uploaded.etag())
                .set(MediaAsset::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            MediaAsset current = requireOwned(userId, assetId);
            if (READY.equals(current.getStatus())) return toResponse(current, true);
            if (!UPLOADING.equals(current.getStatus())) {
                throw new BusinessException("MEDIA_STATE_INVALID", "当前文件状态不能完成上传");
            }
            return promoteAndComplete(current, temporaryKeyFor(current.getObjectKey()),
                    current.getObjectKey(), current.getEtag());
        }
        asset.setObjectKey(finalKey);
        asset.setEtag(uploaded.etag());
        return promoteAndComplete(asset, temporaryKey, finalKey, uploaded.etag());
    }

    public MediaResponses.Asset upload(Long userId, MultipartFile file, String bizType, Long bizId) {
        if (file == null || file.isEmpty()) throw new BusinessException("MEDIA_EMPTY", "请选择需要上传的文件");
        String contentType = file.getContentType();
        validate(file.getOriginalFilename(), contentType, file.getSize(), bizType);
        String normalizedBizType = bizType.trim().toUpperCase(Locale.ROOT);
        Long normalizedBizId = validateGenericBusiness(userId, normalizedBizType, bizId, contentType, file.getSize());
        return storeUpload(userId, file, normalizedBizType, normalizedBizId);
    }

    private MediaResponses.Asset storeUpload(Long userId, MultipartFile file, String bizType, Long bizId) {
        String contentType = file.getContentType();
        validate(file.getOriginalFilename(), contentType, file.getSize(), bizType);
        String objectKey = createObjectKey(userId, bizType, file.getOriginalFilename());
        StoredObjectMetadata metadata;
        try (InputStream input = file.getInputStream()) {
            metadata = storage.upload(objectKey, input, file.getSize(), contentType);
        } catch (IOException exception) {
            throw new BusinessException("MEDIA_READ_ERROR", "读取上传文件失败");
        }

        MediaAsset asset = newAsset(userId, objectKey, file.getOriginalFilename(), contentType,
                file.getSize(), bizType, bizId, READY);
        asset.setEtag(metadata.etag());
        try {
            mapper.insert(asset);
        } catch (RuntimeException exception) {
            try { storage.delete(objectKey); } catch (RuntimeException ignored) { exception.addSuppressed(ignored); }
            throw exception;
        }
        return toResponse(asset, true);
    }

    public MediaResponses.Asset uploadAvatar(Long userId, MultipartFile file) {
        String contentType = file == null ? null : file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                || "image/svg+xml".equalsIgnoreCase(contentType)) {
            throw new BusinessException("AVATAR_TYPE_INVALID", "头像仅支持常见图片格式");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException("MEDIA_SIZE_INVALID", "头像大小不能超过 5MB");
        }
        return storeUpload(userId, file, "AVATAR", userId);
    }

    public MediaResponses.Asset uploadCourseVideo(Long userId, Long lessonId, MultipartFile file) {
        String contentType = file == null ? null : file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw new BusinessException("COURSE_VIDEO_TYPE_INVALID", "课程视频仅支持视频文件");
        }
        return storeUpload(userId, file, "COURSE_LESSON", lessonId);
    }

    public String readyAccessUrl(Long assetId) {
        if (assetId == null) return null;
        MediaAsset asset = mapper.selectById(assetId);
        if (asset == null || !READY.equals(asset.getStatus())) return null;
        return accessFor(asset).url().toString();
    }

    public MediaResponses.Asset detail(Long userId, Long assetId) {
        return toResponse(requireOwned(userId, assetId), true);
    }

    public void delete(Long userId, Long assetId) {
        MediaAsset asset = requireOwned(userId, assetId);
        if (READY.equals(asset.getStatus()) || UPLOADING.equals(asset.getStatus())) {
            storage.delete(asset.getObjectKey());
        }
        mapper.deleteById(assetId);
    }

    private MediaAsset requireOwned(Long userId, Long assetId) {
        MediaAsset asset = mapper.selectById(assetId);
        if (asset == null || !userId.equals(asset.getOwnerId())) {
            throw new BusinessException("MEDIA_NOT_FOUND", "文件不存在");
        }
        return asset;
    }

    private MediaResponses.Asset toResponse(MediaAsset asset, boolean includeUrl) {
        String url = null;
        Instant expiresAt = null;
        if (includeUrl && READY.equals(asset.getStatus())) {
            StoredObjectAccess access = accessFor(asset);
            url = access.url().toString();
            expiresAt = access.expiresAt();
        }
        return new MediaResponses.Asset(asset.getId(), asset.getOriginalName(), asset.getContentType(),
                asset.getFileSize(), asset.getStatus(), asset.getBizType(), asset.getBizId(), url,
                expiresAt, asset.getCreatedAt());
    }

    private MediaAsset newAsset(Long userId, String objectKey, String filename, String contentType,
                                long size, String bizType, Long bizId, String status) {
        LocalDateTime now = LocalDateTime.now();
        MediaAsset asset = new MediaAsset();
        asset.setOwnerId(userId);
        asset.setProvider("ALIYUN_OSS");
        asset.setBucketName(properties.getBucket());
        asset.setObjectKey(objectKey);
        asset.setOriginalName(filename.trim());
        asset.setContentType(contentType.trim().toLowerCase(Locale.ROOT));
        asset.setFileSize(size);
        asset.setStatus(status);
        asset.setBizType(bizType);
        asset.setBizId(bizId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(0);
        return asset;
    }

    private void validate(String filename, String contentType, long size, String bizType) {
        if (filename == null || filename.isBlank() || filename.length() > 255) {
            throw new BusinessException("MEDIA_NAME_INVALID", "文件名不合法");
        }
        if (size <= 0 || size > properties.getMaxFileSize()) {
            throw new BusinessException("MEDIA_SIZE_INVALID", "文件大小超出允许范围");
        }
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        boolean allowed = normalizedType.startsWith("image/") && !"image/svg+xml".equals(normalizedType)
                || normalizedType.startsWith("video/")
                || normalizedType.startsWith("audio/")
                || EXTRA_CONTENT_TYPES.contains(normalizedType);
        if (!allowed) throw new BusinessException("MEDIA_TYPE_INVALID", "不支持该文件类型");
        String normalizedBizType = bizType == null ? "" : bizType.trim().toUpperCase(Locale.ROOT);
        if (!BIZ_TYPE.matcher(normalizedBizType).matches()) {
            throw new BusinessException("MEDIA_BIZ_TYPE_INVALID", "业务类型格式不合法");
        }
    }

    private String createObjectKey(Long userId, String bizType, String filename) {
        String extension = extensionOf(filename);
        String suffix = extension.isEmpty() ? "" : "." + extension;
        return "uploads/" + bizType.toLowerCase(Locale.ROOT) + "/"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/"
                + userId + "/" + UUID.randomUUID().toString().replace("-", "") + suffix;
    }

    private String createTemporaryObjectKey(Long userId, String bizType, String filename) {
        return createObjectKey(userId, bizType, filename).replaceFirst("^uploads/", "uploads/.tmp/");
    }

    private boolean isTemporaryObjectKey(String objectKey) {
        return objectKey.startsWith("uploads/.tmp/");
    }

    private String finalKeyFor(String temporaryKey) {
        return temporaryKey.replaceFirst("^uploads/\\.tmp/", "uploads/");
    }

    private String temporaryKeyFor(String finalKey) {
        if (isTemporaryObjectKey(finalKey)) return finalKey;
        return finalKey.replaceFirst("^uploads/", "uploads/.tmp/");
    }

    private MediaResponses.Asset promoteAndComplete(MediaAsset asset, String temporaryKey,
                                                     String finalKey, String sourceEtag) {
        if (sourceEtag == null || sourceEtag.isBlank()) {
            throw new BusinessException("MEDIA_STATE_INVALID", "上传文件缺少校验信息");
        }
        StoredObjectMetadata promoted = storage.promote(temporaryKey, finalKey, sourceEtag);
        validatePromotedObject(asset, promoted);
        storage.delete(temporaryKey);
        int completed = mapper.update(null, new LambdaUpdateWrapper<MediaAsset>()
                .eq(MediaAsset::getId, asset.getId())
                .eq(MediaAsset::getOwnerId, asset.getOwnerId())
                .eq(MediaAsset::getStatus, UPLOADING)
                .eq(MediaAsset::getObjectKey, finalKey)
                .set(MediaAsset::getStatus, READY)
                .set(MediaAsset::getEtag, promoted.etag())
                .set(MediaAsset::getUpdatedAt, LocalDateTime.now()));
        if (completed == 0) {
            MediaAsset current = requireOwned(asset.getOwnerId(), asset.getId());
            if (READY.equals(current.getStatus())) return toResponse(current, true);
            throw new BusinessException("MEDIA_STATE_INVALID", "上传状态已发生变化，请重试");
        }
        asset.setObjectKey(finalKey);
        asset.setStatus(READY);
        asset.setEtag(promoted.etag());
        asset.setUpdatedAt(LocalDateTime.now());
        return toResponse(asset, true);
    }

    private MediaResponses.Asset rejectLegacyUpload(MediaAsset asset) {
        mapper.update(null, new LambdaUpdateWrapper<MediaAsset>()
                .eq(MediaAsset::getId, asset.getId())
                .eq(MediaAsset::getOwnerId, asset.getOwnerId())
                .eq(MediaAsset::getStatus, UPLOADING)
                .eq(MediaAsset::getObjectKey, asset.getObjectKey())
                .set(MediaAsset::getStatus, FAILED)
                .set(MediaAsset::getUpdatedAt, LocalDateTime.now()));
        storage.delete(asset.getObjectKey());
        throw new BusinessException("MEDIA_UPLOAD_EXPIRED", "旧版上传地址已失效，请重新上传");
    }

    private void validateUploadedObject(MediaAsset asset, StoredObjectMetadata actual, String objectKey) {
        if (metadataMatches(asset, actual)) return;
        mapper.update(null, new LambdaUpdateWrapper<MediaAsset>()
                .eq(MediaAsset::getId, asset.getId())
                .eq(MediaAsset::getOwnerId, asset.getOwnerId())
                .eq(MediaAsset::getStatus, UPLOADING)
                .set(MediaAsset::getStatus, FAILED)
                .set(MediaAsset::getUpdatedAt, LocalDateTime.now()));
        storage.delete(objectKey);
        throw new BusinessException("MEDIA_MISMATCH", "上传文件与申请信息不一致，已清理");
    }

    private void validatePromotedObject(MediaAsset asset, StoredObjectMetadata actual) {
        if (!metadataMatches(asset, actual)) {
            throw new BusinessException("MEDIA_MISMATCH", "转存文件与申请信息不一致");
        }
    }

    private boolean metadataMatches(MediaAsset asset, StoredObjectMetadata actual) {
        return actual.size() == asset.getFileSize()
                && sameContentType(asset.getContentType(), actual.contentType());
    }

    private StoredObjectAccess accessFor(MediaAsset asset) {
        return storage.accessUrl(asset.getObjectKey(), properties.getDownloadUrlTtl(),
                PUBLIC_BIZ_TYPES.contains(asset.getBizType()));
    }

    private Long validateGenericBusiness(Long userId, String bizType, Long bizId,
                                         String contentType, long size) {
        if (!"AVATAR".equals(bizType)) {
            throw new BusinessException("MEDIA_BIZ_FORBIDDEN", "该业务类型只能通过专用管理接口上传");
        }
        if (bizId != null && !userId.equals(bizId)) {
            throw new BusinessException("MEDIA_BIZ_FORBIDDEN", "不能为其他用户上传头像");
        }
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                || "image/svg+xml".equalsIgnoreCase(contentType)) {
            throw new BusinessException("AVATAR_TYPE_INVALID", "头像仅支持常见图片格式");
        }
        if (size > MAX_AVATAR_SIZE) {
            throw new BusinessException("MEDIA_SIZE_INVALID", "头像大小不能超过 5MB");
        }
        return userId;
    }

    private String extensionOf(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) return "";
        String extension = filename.substring(index + 1).toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(extension).matches() ? extension : "";
    }

    private boolean sameContentType(String expected, String actual) {
        if (actual == null) return false;
        return expected.equalsIgnoreCase(actual.split(";", 2)[0].trim());
    }
}
