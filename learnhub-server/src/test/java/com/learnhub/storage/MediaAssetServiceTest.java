package com.learnhub.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.storage.config.OssStorageProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MediaAssetServiceTest {
    @Mock private MediaAssetMapper mapper;
    @Mock private ObjectStorageService storage;
    private MediaAssetService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "media-test"), MediaAsset.class);
        OssStorageProperties properties = new OssStorageProperties();
        properties.setBucket("learnhub-test");
        properties.setUploadUrlTtl(Duration.ofMinutes(10));
        properties.setDownloadUrlTtl(Duration.ofMinutes(20));
        service = new MediaAssetService(mapper, storage, properties);
    }

    @Test
    void createsOwnedUploadTicketWithSignedHeaders() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        when(storage.presignUpload(any(), any(), any())).thenReturn(new PresignedUpload(
                URI.create("https://example.oss-cn-hangzhou.aliyuncs.com/key"), expiresAt,
                Map.of("Content-Type", "image/png")));
        when(mapper.insert(any(MediaAsset.class))).thenAnswer(invocation -> {
            MediaAsset asset = invocation.getArgument(0);
            asset.setId(99L);
            return 1;
        });

        MediaResponses.UploadTicket ticket = service.createUpload(7L,
                new MediaRequests.PresignUpload("头像.png", "image/png", 128, "avatar", null));

        assertThat(ticket.assetId()).isEqualTo(99L);
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
        assertThat(ticket.objectKey()).startsWith("uploads/.tmp/avatar/").endsWith(".png");
        verify(mapper).insert(any(MediaAsset.class));
    }

    @Test
    void rejectsSvgBeforeCallingStorage() {
        assertThatThrownBy(() -> service.createUpload(7L,
                new MediaRequests.PresignUpload("payload.svg", "image/svg+xml", 128, "AVATAR", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MEDIA_TYPE_INVALID");
    }

    @Test
    void completesUploadOnlyWhenOssMetadataMatches() {
        MediaAsset asset = uploadingAsset();
        asset.setObjectKey("uploads/.tmp/course_lesson/key.mp4");
        when(mapper.selectById(11L)).thenReturn(asset);
        when(storage.metadata("uploads/.tmp/course_lesson/key.mp4"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-1"));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(storage.promote("uploads/.tmp/course_lesson/key.mp4", "uploads/course_lesson/key.mp4", "etag-1"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-final"));
        Instant expiresAt = Instant.now().plusSeconds(1200);
        when(storage.accessUrl(any(), any(), eq(false)))
                .thenReturn(new StoredObjectAccess(URI.create("https://download.example/key"), expiresAt));

        MediaResponses.Asset response = service.completeUpload(7L, 11L);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.url()).isEqualTo("https://download.example/key");
        assertThat(response.urlExpiresAt()).isEqualTo(expiresAt);
        assertThat(asset.getObjectKey()).isEqualTo("uploads/course_lesson/key.mp4");
        assertThat(asset.getEtag()).isEqualTo("etag-final");
        verify(storage).delete("uploads/.tmp/course_lesson/key.mp4");
    }

    @Test
    void rejectsNonImageAvatar() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.mp4", "video/mp4", new byte[] {1});

        assertThatThrownBy(() -> service.uploadAvatar(7L, file))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("AVATAR_TYPE_INVALID");
    }

    @Test
    void rejectsNonVideoCourseMedia() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", new byte[] {1});

        assertThatThrownBy(() -> service.uploadCourseVideo(7L, 3001L, file))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("COURSE_VIDEO_TYPE_INVALID");
    }

    @Test
    void resolvesOnlyReadyAssetUrl() {
        MediaAsset asset = uploadingAsset();
        asset.setStatus("READY");
        when(mapper.selectById(11L)).thenReturn(asset);
        when(storage.accessUrl(any(), any(), eq(false))).thenReturn(new StoredObjectAccess(
                URI.create("https://download.example/key"), Instant.now().plusSeconds(1200)));

        assertThat(service.readyAccessUrl(11L)).isEqualTo("https://download.example/key");
    }

    @Test
    void publicAvatarResponseDoesNotClaimAnExpiry() {
        MediaAsset asset = uploadingAsset();
        asset.setStatus("READY");
        asset.setBizType("AVATAR");
        when(mapper.selectById(11L)).thenReturn(asset);
        when(storage.accessUrl(any(), any(), eq(true))).thenReturn(new StoredObjectAccess(
                URI.create("https://cdn.example/avatar.png"), null));

        MediaResponses.Asset response = service.detail(7L, 11L);

        assertThat(response.url()).isEqualTo("https://cdn.example/avatar.png");
        assertThat(response.urlExpiresAt()).isNull();
    }

    @Test
    void rejectsGenericCourseUploadBecauseItMustUseAdminFlow() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.mp4", "video/mp4", new byte[] {1});

        assertThatThrownBy(() -> service.upload(7L, file, "COURSE_LESSON", 3001L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MEDIA_BIZ_FORBIDDEN");
        verify(storage, never()).upload(any(), any(), anyLong(), any());
    }

    @Test
    void resumesPromotionAfterDatabaseClaimWasPersisted() {
        MediaAsset asset = uploadingAsset();
        asset.setObjectKey("uploads/course_lesson/key.mp4");
        asset.setEtag("etag-source");
        when(mapper.selectById(11L)).thenReturn(asset);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(storage.promote("uploads/.tmp/course_lesson/key.mp4", "uploads/course_lesson/key.mp4", "etag-source"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-final"));
        when(storage.accessUrl(any(), any(), eq(false))).thenReturn(new StoredObjectAccess(
                URI.create("https://download.example/key"), Instant.now().plusSeconds(1200)));

        MediaResponses.Asset response = service.completeUpload(7L, 11L);

        assertThat(response.status()).isEqualTo("READY");
        verify(storage).delete("uploads/.tmp/course_lesson/key.mp4");
    }

    @Test
    void rejectsLegacyPendingUploadInsteadOfPublishingItsReusableTarget() {
        MediaAsset asset = uploadingAsset();
        when(mapper.selectById(11L)).thenReturn(asset);

        assertThatThrownBy(() -> service.completeUpload(7L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MEDIA_UPLOAD_EXPIRED");
        verify(storage).delete("uploads/course_lesson/key.mp4");
        verify(storage, never()).metadata(any());
    }

    @Test
    void completesWhenAnotherRequestWonThePromotionClaim() {
        MediaAsset temporary = uploadingAsset();
        temporary.setObjectKey("uploads/.tmp/course_lesson/key.mp4");
        MediaAsset claimed = uploadingAsset();
        claimed.setObjectKey("uploads/course_lesson/key.mp4");
        claimed.setEtag("etag-source");
        when(mapper.selectById(11L)).thenReturn(temporary, claimed);
        when(storage.metadata("uploads/.tmp/course_lesson/key.mp4"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-source"));
        when(mapper.update(isNull(), any())).thenReturn(0, 1);
        when(storage.promote("uploads/.tmp/course_lesson/key.mp4", "uploads/course_lesson/key.mp4", "etag-source"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-final"));
        when(storage.accessUrl(any(), any(), eq(false))).thenReturn(new StoredObjectAccess(
                URI.create("https://download.example/key"), Instant.now().plusSeconds(1200)));

        MediaResponses.Asset response = service.completeUpload(7L, 11L);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.id()).isEqualTo(11L);
    }

    private MediaAsset uploadingAsset() {
        MediaAsset asset = new MediaAsset();
        asset.setId(11L);
        asset.setOwnerId(7L);
        asset.setObjectKey("uploads/course_lesson/key.mp4");
        asset.setOriginalName("lesson.mp4");
        asset.setContentType("video/mp4");
        asset.setFileSize(1024L);
        asset.setStatus("UPLOADING");
        asset.setBizType("COURSE_LESSON");
        asset.setCreatedAt(java.time.LocalDateTime.now());
        return asset;
    }
}
