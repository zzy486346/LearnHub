package com.learnhub.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MediaAssetServiceTest {
    @Mock private MediaAssetMapper mapper;
    @Mock private ObjectStorageService storage;
    private MediaAssetService service;

    @BeforeEach
    void setUp() {
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
        assertThat(ticket.objectKey()).startsWith("uploads/avatar/").endsWith(".png");
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
        when(mapper.selectById(11L)).thenReturn(asset);
        when(storage.metadata("uploads/course/key.mp4"))
                .thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag-1"));
        when(storage.accessUrl(any(), any())).thenReturn(URI.create("https://download.example/key"));

        MediaResponses.Asset response = service.completeUpload(7L, 11L);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.url()).isEqualTo("https://download.example/key");
        assertThat(asset.getEtag()).isEqualTo("etag-1");
        verify(mapper).updateById(asset);
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
        when(storage.accessUrl(any(), any())).thenReturn(URI.create("https://download.example/key"));

        assertThat(service.readyAccessUrl(11L)).isEqualTo("https://download.example/key");
    }

    private MediaAsset uploadingAsset() {
        MediaAsset asset = new MediaAsset();
        asset.setId(11L);
        asset.setOwnerId(7L);
        asset.setObjectKey("uploads/course/key.mp4");
        asset.setOriginalName("lesson.mp4");
        asset.setContentType("video/mp4");
        asset.setFileSize(1024L);
        asset.setStatus("UPLOADING");
        asset.setBizType("COURSE");
        asset.setCreatedAt(java.time.LocalDateTime.now());
        return asset;
    }
}
