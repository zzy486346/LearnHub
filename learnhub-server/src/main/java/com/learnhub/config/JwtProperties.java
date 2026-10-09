package com.learnhub.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "learnhub.jwt")
public class JwtProperties {
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(7);
    private Resource privateKeyLocation;
    private Resource publicKeyLocation;
    private boolean requireConfiguredKeys;

    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    public Resource getPrivateKeyLocation() { return privateKeyLocation; }
    public void setPrivateKeyLocation(Resource privateKeyLocation) { this.privateKeyLocation = privateKeyLocation; }
    public Resource getPublicKeyLocation() { return publicKeyLocation; }
    public void setPublicKeyLocation(Resource publicKeyLocation) { this.publicKeyLocation = publicKeyLocation; }
    public boolean isRequireConfiguredKeys() { return requireConfiguredKeys; }
    public void setRequireConfiguredKeys(boolean requireConfiguredKeys) {
        this.requireConfiguredKeys = requireConfiguredKeys;
    }
}
