package com.learnhub.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.CopyObjectRequest;
import com.aliyun.sdk.service.oss2.models.CopyObjectResult;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.learnhub.storage.config.OssStorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AliyunOssObjectStorageServiceTest {
    private OSSClient client;
    private AliyunOssObjectStorageService storage;

    @BeforeEach
    void setUp() {
        client = mock(OSSClient.class);
        OssStorageProperties properties = new OssStorageProperties();
        properties.setBucket("learnhub-test");
        properties.setPublicBaseUrl("https://cdn.example/");
        storage = new AliyunOssObjectStorageService(client, properties);
    }

    @Test
    void returnsPublicUrlOnlyWhenCallerAllowsIt() {
        StoredObjectAccess access = storage.accessUrl("uploads/avatar/a.png", Duration.ofMinutes(20), true);

        assertThat(access.url()).hasToString("https://cdn.example/uploads/avatar/a.png");
        assertThat(access.expiresAt()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void realSdkDoesNotSignContentLengthSoCompletionMustValidateSize() throws Exception {
        try (OSSClient realClient = OSSClient.newBuilder()
                .credentialsProvider(new StaticCredentialsProvider("test-access-key", "test-access-secret"))
                .region("cn-hangzhou")
                .endpoint("https://oss-cn-hangzhou.aliyuncs.com")
                .build()) {
            PutObjectRequest request = PutObjectRequest.newBuilder()
                    .bucket("learnhub-test")
                    .key("uploads/.tmp/avatar/a.png")
                    .contentType("image/png")
                    .contentLength(1024)
                    .build();
            PresignResult result = realClient.presign(request, PresignOptions.newBuilder()
                    .expiration(Instant.now().plusSeconds(600))
                    .build());

            assertThat(result.signedHeaders().orElseThrow())
                    .containsEntry("Content-Type", "image/png")
                    .doesNotContainKey("Content-Length")
                    .doesNotContainKey("content-length");
        }
    }

    @Test
    void signsPrivateObjectEvenWhenPublicBaseUrlIsConfigured() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        PresignResult result = mock(PresignResult.class);
        when(result.url()).thenReturn("https://oss.example/signed-video");
        when(result.expiration()).thenReturn(Optional.of(expiresAt));
        when(client.presign(any(GetObjectRequest.class), any(PresignOptions.class))).thenReturn(result);

        StoredObjectAccess access = storage.accessUrl(
                "uploads/course_lesson/video.mp4", Duration.ofMinutes(10), false);

        assertThat(access.url()).hasToString("https://oss.example/signed-video");
        assertThat(access.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void promotesWithSourceEtagAndForbidsTargetOverwrite() {
        when(client.copyObject(any(CopyObjectRequest.class))).thenReturn(mock(CopyObjectResult.class));
        HeadObjectResult head = mock(HeadObjectResult.class);
        when(head.contentLength()).thenReturn(1024L);
        when(head.contentType()).thenReturn("video/mp4");
        when(head.eTag()).thenReturn("etag-final");
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head);

        StoredObjectMetadata metadata = storage.promote(
                "uploads/.tmp/course_lesson/video.mp4",
                "uploads/course_lesson/video.mp4", "etag-source");

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().sourceKey()).isEqualTo("uploads/.tmp/course_lesson/video.mp4");
        assertThat(request.getValue().key()).isEqualTo("uploads/course_lesson/video.mp4");
        assertThat(request.getValue().copySourceIfMatch()).isEqualTo("etag-source");
        assertThat(request.getValue().forbidOverwrite()).isEqualTo("true");
        assertThat(metadata).isEqualTo(new StoredObjectMetadata(1024, "video/mp4", "etag-final"));
    }

    @Test
    void treatsExistingTargetAsSuccessfulConcurrentPromotion() {
        when(client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(new IllegalStateException("target already exists"));
        HeadObjectResult head = mock(HeadObjectResult.class);
        when(head.contentLength()).thenReturn(1024L);
        when(head.contentType()).thenReturn("video/mp4");
        when(head.eTag()).thenReturn("etag-final");
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head);

        StoredObjectMetadata metadata = storage.promote(
                "uploads/.tmp/course_lesson/video.mp4",
                "uploads/course_lesson/video.mp4", "etag-source");

        assertThat(metadata).isEqualTo(new StoredObjectMetadata(1024, "video/mp4", "etag-final"));
    }
}
