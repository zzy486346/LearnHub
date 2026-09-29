package com.learnhub.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class JwtTokenServiceTest {
    private JwtTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        JwtProperties properties = new JwtProperties();
        service = new JwtTokenService(properties, redis, Clock.systemUTC());
        service.initializeKeys();
    }

    @Test
    void issuesRs256AccessAndRefreshTokensWithExpectedClaims() {
        TokenResponse response = service.issue(42L, "learner", 4);

        Jws<Claims> access = service.require(response.accessToken(), "access");
        Jws<Claims> refresh = service.require(response.refreshToken(), "refresh");

        assertThat(access.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(access.getPayload().getSubject()).isEqualTo("42");
        assertThat(access.getPayload().get("username", String.class)).isEqualTo("learner");
        assertThat(service.tokenVersion(access)).isEqualTo(4);
        assertThat(service.tokenVersion(refresh)).isEqualTo(4);
        assertThat(refresh.getPayload().get("type", String.class)).isEqualTo("refresh");
        assertThat(response.accessExpiresIn()).isEqualTo(900);
        assertThat(response.refreshExpiresIn()).isEqualTo(604800);
    }
}
