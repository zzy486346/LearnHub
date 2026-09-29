package com.learnhub.storage.config;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OSSClientBuilder;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.learnhub.storage.AliyunOssObjectStorageService;
import com.learnhub.storage.DisabledObjectStorageService;
import com.learnhub.storage.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(OssStorageProperties.class)
public class OssStorageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "learnhub.storage.oss", name = "enabled", havingValue = "true")
    OSSClient aliyunOssClient(OssStorageProperties properties) {
        requireText(properties.getRegion(), "LEARNHUB_OSS_REGION");
        requireText(properties.getBucket(), "LEARNHUB_OSS_BUCKET");
        requireText(properties.getAccessKeyId(), "OSS_ACCESS_KEY_ID");
        requireText(properties.getAccessKeySecret(), "OSS_ACCESS_KEY_SECRET");

        OSSClientBuilder builder = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider(properties))
                .region(properties.getRegion());
        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }

    private StaticCredentialsProvider credentialsProvider(OssStorageProperties properties) {
        if (StringUtils.hasText(properties.getSecurityToken())) {
            return new StaticCredentialsProvider(properties.getAccessKeyId(),
                    properties.getAccessKeySecret(), properties.getSecurityToken());
        }
        return new StaticCredentialsProvider(properties.getAccessKeyId(), properties.getAccessKeySecret());
    }

    @Bean
    @ConditionalOnProperty(prefix = "learnhub.storage.oss", name = "enabled", havingValue = "true")
    ObjectStorageService aliyunObjectStorageService(OSSClient client, OssStorageProperties properties) {
        return new AliyunOssObjectStorageService(client, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "learnhub.storage.oss", name = "enabled", havingValue = "false", matchIfMissing = true)
    ObjectStorageService disabledObjectStorageService() {
        return new DisabledObjectStorageService();
    }

    private void requireText(String value, String environmentName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(environmentName + " must be configured when OSS storage is enabled");
        }
    }
}
