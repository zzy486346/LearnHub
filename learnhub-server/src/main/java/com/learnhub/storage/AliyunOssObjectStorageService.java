package com.learnhub.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectResult;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.storage.config.OssStorageProperties;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AliyunOssObjectStorageService implements ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(AliyunOssObjectStorageService.class);
    private final OSSClient client;
    private final OssStorageProperties properties;

    public AliyunOssObjectStorageService(OSSClient client, OssStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        try {
            PutObjectRequest request = PutObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .build();
            PresignResult result = client.presign(request, presignOptions(expiresAt));
            return new PresignedUpload(URI.create(result.url()),
                    result.expiration().orElse(expiresAt),
                    result.signedHeaders().orElse(Map.of()));
        } catch (Exception exception) {
            throw storageFailure("生成上传地址失败", exception);
        }
    }

    @Override
    public StoredObjectMetadata upload(String objectKey, InputStream input, long size, String contentType) {
        try {
            PutObjectResult result = client.putObject(PutObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .body(BinaryData.fromStream(input, size))
                    .build());
            return new StoredObjectMetadata(size, contentType, result.eTag());
        } catch (RuntimeException exception) {
            throw storageFailure("文件上传失败", exception);
        }
    }

    @Override
    public StoredObjectMetadata metadata(String objectKey) {
        try {
            HeadObjectResult metadata = client.headObject(HeadObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
            return new StoredObjectMetadata(metadata.contentLength(), metadata.contentType(), metadata.eTag());
        } catch (RuntimeException exception) {
            throw storageFailure("无法确认 OSS 文件状态", exception);
        }
    }

    @Override
    public URI accessUrl(String objectKey, Duration ttl) {
        String publicBaseUrl = properties.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return URI.create(publicBaseUrl.replaceAll("/+$", "") + "/" + objectKey);
        }
        try {
            Instant expiresAt = Instant.now().plus(ttl);
            PresignResult result = client.presign(GetObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build(), presignOptions(expiresAt));
            return URI.create(result.url());
        } catch (Exception exception) {
            throw storageFailure("生成文件访问地址失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
        } catch (RuntimeException exception) {
            throw storageFailure("删除 OSS 文件失败", exception);
        }
    }

    private BusinessException storageFailure(String message, Exception exception) {
        ServiceException serviceException = ServiceException.asCause(exception);
        if (serviceException != null) {
            log.warn("OSS request failed: code={}, requestId={}",
                    serviceException.errorCode(), serviceException.requestId());
        } else {
            log.warn("OSS request failed: {}", exception.getClass().getSimpleName());
        }
        return new BusinessException("STORAGE_ERROR", message);
    }

    private PresignOptions presignOptions(Instant expiresAt) {
        return PresignOptions.newBuilder().expiration(expiresAt).build();
    }
}
