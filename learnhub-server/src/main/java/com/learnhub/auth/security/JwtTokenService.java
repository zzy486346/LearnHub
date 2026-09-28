package com.learnhub.auth.security;

import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String DENY_PREFIX = "auth:deny:";
    private final JwtProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    @Autowired
    public JwtTokenService(JwtProperties properties, StringRedisTemplate redisTemplate) {
        this(properties, redisTemplate, Clock.systemUTC());
    }

    JwtTokenService(JwtProperties properties, StringRedisTemplate redisTemplate, Clock clock) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @PostConstruct
    void initializeKeys() {
        try {
            boolean privateConfigured = properties.getPrivateKeyLocation() != null
                    && properties.getPrivateKeyLocation().exists();
            boolean publicConfigured = properties.getPublicKeyLocation() != null
                    && properties.getPublicKeyLocation().exists();
            if (privateConfigured != publicConfigured) {
                throw new IllegalStateException("JWT private and public keys must be configured together");
            }
            if (privateConfigured) {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                        decodePem(properties.getPrivateKeyLocation().getContentAsString(StandardCharsets.UTF_8))));
                publicKey = factory.generatePublic(new X509EncodedKeySpec(
                        decodePem(properties.getPublicKeyLocation().getContentAsString(StandardCharsets.UTF_8))));
            } else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                privateKey = pair.getPrivate();
                publicKey = pair.getPublic();
                log.warn("JWT RSA keys are not configured; generated an ephemeral development key pair");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid RSA JWT key configuration", exception);
        }
    }

    public TokenResponse issue(Long userId, String username) {
        Token access = create(userId, username, "access", properties.getAccessTokenTtl());
        Token refresh = create(userId, username, "refresh", properties.getRefreshTokenTtl());
        redisTemplate.opsForValue().set(REFRESH_PREFIX + refresh.jti(), userId.toString(), properties.getRefreshTokenTtl());
        return new TokenResponse("Bearer", access.value(), properties.getAccessTokenTtl().toSeconds(),
                refresh.value(), properties.getRefreshTokenTtl().toSeconds());
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
    }

    public Jws<Claims> require(String token, String expectedType) {
        Jws<Claims> parsed = parse(token);
        if (!expectedType.equals(parsed.getPayload().get("type", String.class))) {
            throw new JwtException("Unexpected token type");
        }
        return parsed;
    }

    public boolean isAccessDenied(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(DENY_PREFIX + jti));
    }

    public boolean consumeRefresh(Jws<Claims> token) {
        String userId = token.getPayload().getSubject();
        return userId.equals(redisTemplate.opsForValue().getAndDelete(REFRESH_PREFIX + token.getPayload().getId()));
    }

    public void revokeRefresh(String jti) {
        redisTemplate.delete(REFRESH_PREFIX + jti);
    }

    public void denyAccess(Jws<Claims> token) {
        Duration remaining = Duration.between(clock.instant(), token.getPayload().getExpiration().toInstant());
        if (!remaining.isNegative() && !remaining.isZero()) {
            redisTemplate.opsForValue().set(DENY_PREFIX + token.getPayload().getId(), "1", remaining);
        }
    }

    private Token create(Long userId, String username, String type, Duration ttl) {
        Instant now = clock.instant();
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .subject(userId.toString()).claim("username", username).claim("type", type)
                .id(jti).issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(privateKey, Jwts.SIG.RS256).compact();
        return new Token(value, jti);
    }

    private byte[] decodePem(String value) {
        String normalized = value.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private record Token(String value, String jti) {}
}
