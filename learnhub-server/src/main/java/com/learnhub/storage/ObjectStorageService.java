package com.learnhub.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public interface ObjectStorageService {
    PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl);
    StoredObjectMetadata upload(String objectKey, InputStream input, long size, String contentType);
    StoredObjectMetadata metadata(String objectKey);
    URI accessUrl(String objectKey, Duration ttl);
    void delete(String objectKey);
}
