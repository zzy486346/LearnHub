package com.learnhub.storage;

import com.learnhub.common.exception.BusinessException;
import java.io.InputStream;
import java.time.Duration;

public class DisabledObjectStorageService implements ObjectStorageService {
    private BusinessException disabled() {
        return new BusinessException("STORAGE_DISABLED", "文件存储尚未配置");
    }

    @Override public PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl) { throw disabled(); }
    @Override public StoredObjectMetadata upload(String objectKey, InputStream input, long size, String contentType) { throw disabled(); }
    @Override public StoredObjectMetadata metadata(String objectKey) { throw disabled(); }
    @Override public StoredObjectMetadata promote(String sourceKey, String targetKey, String expectedSourceEtag) { throw disabled(); }
    @Override public StoredObjectAccess accessUrl(String objectKey, Duration ttl, boolean allowPublicUrl) { throw disabled(); }
    @Override public void delete(String objectKey) { throw disabled(); }
}
