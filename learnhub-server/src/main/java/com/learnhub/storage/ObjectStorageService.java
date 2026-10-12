package com.learnhub.storage;

import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {
    PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl);
    StoredObjectMetadata upload(String objectKey, InputStream input, long size, String contentType);
    StoredObjectMetadata metadata(String objectKey);
    StoredObjectMetadata promote(String sourceKey, String targetKey, String expectedSourceEtag);
    StoredObjectAccess accessUrl(String objectKey, Duration ttl, boolean allowPublicUrl);
    void delete(String objectKey);
}
