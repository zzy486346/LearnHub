package com.learnhub.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class JwtTokenServiceTest {
    private static Path privateKeyPath;
    private static Path publicKeyPath;
    private static Path otherPublicKeyPath;
    private static Path weakPrivateKeyPath;
    private static Path weakPublicKeyPath;
    private JwtTokenService service;

    @BeforeAll
    static void createTestKeys(@TempDir Path tempDir) throws Exception {
        KeyPair primary = generateKeyPair(2048);
        KeyPair other = generateKeyPair(2048);
        KeyPair weak = generateKeyPair(1024);
        privateKeyPath = writePem(tempDir.resolve("jwt-private.pem"), "PRIVATE KEY", primary.getPrivate().getEncoded());
        publicKeyPath = writePem(tempDir.resolve("jwt-public.pem"), "PUBLIC KEY", primary.getPublic().getEncoded());
        otherPublicKeyPath = writePem(tempDir.resolve("jwt-other-public.pem"), "PUBLIC KEY", other.getPublic().getEncoded());
        weakPrivateKeyPath = writePem(tempDir.resolve("jwt-weak-private.pem"), "PRIVATE KEY", weak.getPrivate().getEncoded());
        weakPublicKeyPath = writePem(tempDir.resolve("jwt-weak-public.pem"), "PUBLIC KEY", weak.getPublic().getEncoded());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = newService(new JwtProperties());
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

    @Test
    void rejectsMissingConfiguredKeyFiles() {
        JwtProperties properties = configuredKeys(
                privateKeyPath.resolveSibling("missing-private.pem"),
                publicKeyPath.resolveSibling("missing-public.pem"));

        assertThatThrownBy(() -> newService(properties).initializeKeys())
                .hasRootCauseMessage("Configured JWT RSA key file does not exist");
    }

    @Test
    void rejectsConfiguringOnlyOneKeyPath() {
        JwtProperties properties = new JwtProperties();
        properties.setPrivateKeyLocation(new FileSystemResource(privateKeyPath));

        assertThatThrownBy(() -> newService(properties).initializeKeys())
                .hasRootCauseMessage("JWT private and public keys must be configured together");
    }

    @Test
    void rejectsMismatchedKeyPair() {
        JwtProperties properties = configuredKeys(privateKeyPath, otherPublicKeyPath);

        assertThatThrownBy(() -> newService(properties).initializeKeys())
                .hasRootCauseMessage("JWT RSA private and public keys do not match");
    }

    @Test
    void rejectsWeakRsaKeyPair() {
        JwtProperties properties = configuredKeys(weakPrivateKeyPath, weakPublicKeyPath);

        assertThatThrownBy(() -> newService(properties).initializeKeys())
                .hasRootCauseMessage("JWT RSA keys must be at least 2048 bits");
    }

    @Test
    void productionConfigurationRequiresFixedKeys() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load(
                "application-prod", new ClassPathResource("application-prod.yml"));
        environment.getPropertySources().addFirst(sources.get(0));
        JwtProperties properties = Binder.get(environment)
                .bind("learnhub.jwt", Bindable.of(JwtProperties.class))
                .orElseThrow(() -> new AssertionError("生产 JWT 配置未绑定"));

        assertThat(properties.isRequireConfiguredKeys()).isTrue();
        assertThatThrownBy(() -> newService(properties).initializeKeys())
                .hasRootCauseMessage("Configured JWT RSA keys are required in this environment");
    }

    @Test
    void fixedKeyPairKeepsTokensValidAcrossServiceRestart() {
        JwtProperties properties = configuredKeys(privateKeyPath, publicKeyPath);
        JwtTokenService firstInstance = newService(properties);
        firstInstance.initializeKeys();
        TokenResponse issued = firstInstance.issue(42L, "learner", 7);

        JwtTokenService restartedInstance = newService(configuredKeys(privateKeyPath, publicKeyPath));
        restartedInstance.initializeKeys();

        assertThat(restartedInstance.require(issued.accessToken(), "access").getPayload().getSubject())
                .isEqualTo("42");
        assertThat(restartedInstance.tokenVersion(
                restartedInstance.require(issued.refreshToken(), "refresh"))).isEqualTo(7);
    }

    @SuppressWarnings("unchecked")
    private JwtTokenService newService(JwtProperties properties) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return new JwtTokenService(properties, redis, Clock.systemUTC());
    }

    private static JwtProperties configuredKeys(Path privatePath, Path publicPath) {
        JwtProperties properties = new JwtProperties();
        properties.setPrivateKeyLocation(new FileSystemResource(privatePath));
        properties.setPublicKeyLocation(new FileSystemResource(publicPath));
        return properties;
    }

    private static KeyPair generateKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static Path writePem(Path path, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return Files.writeString(path, "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n");
    }
}
