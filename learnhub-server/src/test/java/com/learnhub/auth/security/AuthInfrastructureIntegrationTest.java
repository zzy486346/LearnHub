package com.learnhub.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.auth.controller.AuthController;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.mapper.UserRoleMapper;
import com.learnhub.auth.service.AuthService;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import com.learnhub.infrastructure.GlobalExceptionHandler;
import com.learnhub.storage.MediaAssetService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "learnhub.auth.it.enabled", matches = "true")
class AuthInfrastructureIntegrationTest {
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String DENY_PREFIX = "auth:deny:";
    private static final String INITIAL_PASSWORD = "InitialPass123";
    private static final String NEW_PASSWORD = "ChangedPass456";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path temporaryDirectory;

    private final List<TestUser> testUsers = new ArrayList<>();
    private final Set<String> redisKeys = new java.util.LinkedHashSet<>();
    private ConfigurableApplicationContext firstInstance;
    private ConfigurableApplicationContext secondInstance;
    private ExecutorService httpExecutor;
    private HttpClient httpClient;
    private Path privateKey;
    private Path publicKey;
    private Instant startedAt;
    private int firstPort = -1;
    private int secondPort = -1;

    @BeforeAll
    void startIsolatedInstances() throws Exception {
        writeTemporaryKeyPair();
        httpExecutor = Executors.newFixedThreadPool(4);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .executor(httpExecutor)
                .build();
        startedAt = Instant.now();
        firstInstance = startInstance();
        firstPort = port(firstInstance);
        secondInstance = startInstance();
        secondPort = port(secondInstance);
        System.out.printf("Auth infrastructure IT started pid=%d at=%s project=%s ports=%d,%d%n",
                ProcessHandle.current().pid(), startedAt, Path.of("").toAbsolutePath(), firstPort, secondPort);
    }

    @AfterEach
    void cleanOwnedState() {
        List<Throwable> failures = new ArrayList<>();
        if (firstInstance != null || secondInstance != null) {
            List<String> ownedRedisKeys = List.copyOf(redisKeys);
            try {
                if (!ownedRedisKeys.isEmpty()) redis().delete(ownedRedisKeys);
                redisKeys.removeAll(ownedRedisKeys);
            } catch (Throwable failure) {
                failures.add(failure);
            }
            for (TestUser user : List.copyOf(testUsers)) {
                try {
                    jdbc().update("DELETE FROM user_role WHERE user_id = ?", user.id());
                    jdbc().update("DELETE FROM user WHERE id = ? AND username = ?", user.id(), user.username());
                    testUsers.remove(user);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        }
        throwIfAny(failures);
    }

    @AfterAll
    void stopIsolatedInstances() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        try {
            cleanOwnedState();
        } catch (Throwable failure) {
            failures.add(failure);
        }
        closeAndCollect(secondInstance, failures);
        secondInstance = null;
        closeAndCollect(firstInstance, failures);
        firstInstance = null;
        if (httpExecutor != null) {
            try {
                httpExecutor.shutdownNow();
                assertTrue(httpExecutor.awaitTermination(10, TimeUnit.SECONDS),
                        "HTTP executor did not terminate");
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        awaitPortReleasedAndCollect(firstPort, failures);
        awaitPortReleasedAndCollect(secondPort, failures);
        System.out.printf("Auth infrastructure IT stopped pid=%d startedAt=%s ports=%d,%d%n",
                ProcessHandle.current().pid(), startedAt, firstPort, secondPort);
        throwIfAnyChecked(failures);
    }

    @Test
    void logoutRevokesOldAccessAndRefreshTokensThroughRealHttpAndRedis() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens tokens = login(firstInstance, user.username(), INITIAL_PASSWORD);

        HttpResult logout = request(secondInstance, "POST", "/api/auth/logout", tokens.accessToken(),
                Map.of("refreshToken", tokens.refreshToken()));

        assertEquals(200, logout.status());
        assertEquals(401, request(firstInstance, "GET", "/api/auth/me", tokens.accessToken(), null).status());
        assertEquals(409, refresh(firstInstance, tokens.refreshToken()).status());
        assertTrue(Boolean.TRUE.equals(redis().hasKey(DENY_PREFIX + accessJti(tokens.accessToken()))));
        assertTrue(Boolean.FALSE.equals(redis().hasKey(REFRESH_PREFIX + refreshJti(tokens.refreshToken()))));
    }

    @Test
    void concurrentRefreshConsumesRealRedisStateExactlyOnceAndCannotBeReplayed() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens original = login(firstInstance, user.username(), INITIAL_PASSWORD);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ConfigurableApplicationContext target = i == 0 ? firstInstance : secondInstance;
                futures.add(callers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS), "Concurrent refresh start timed out");
                    return refresh(target, original.refreshToken());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Concurrent refresh callers did not become ready");
            start.countDown();
            HttpResult first = futures.get(0).get(15, TimeUnit.SECONDS);
            HttpResult second = futures.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(1, List.of(first, second).stream().filter(result -> result.status() == 200).count());
            assertEquals(1, List.of(first, second).stream().filter(result -> result.status() == 409).count());
            List.of(first, second).stream()
                    .filter(result -> result.status() == 200)
                    .map(this::tokens)
                    .forEach(this::track);
            assertEquals(409, refresh(firstInstance, original.refreshToken()).status());
            assertTrue(Boolean.FALSE.equals(redis().hasKey(REFRESH_PREFIX + refreshJti(original.refreshToken()))));
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS), "Refresh callers did not terminate");
        }
    }

    @Test
    void databaseTokenVersionInvalidatesTokensIssuedBeforePasswordChange() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens oldTokens = login(firstInstance, user.username(), INITIAL_PASSWORD);

        HttpResult changed = request(firstInstance, "PUT", "/api/auth/me/password", oldTokens.accessToken(),
                Map.of("currentPassword", INITIAL_PASSWORD, "newPassword", NEW_PASSWORD));

        assertEquals(200, changed.status());
        assertEquals(1, jdbc().queryForObject(
                "SELECT token_version FROM user WHERE id = ?", Integer.class, user.id()));
        assertEquals(401, request(secondInstance, "GET", "/api/auth/me", oldTokens.accessToken(), null).status());
        assertEquals(409, refresh(secondInstance, oldTokens.refreshToken()).status());
        Tokens newTokens = login(secondInstance, user.username(), NEW_PASSWORD);
        assertTrue(!oldTokens.accessToken().equals(newTokens.accessToken()),
                "Password change must issue a different access token");
        assertEquals(200, request(firstInstance, "GET", "/api/auth/me", newTokens.accessToken(), null).status());
    }

    @Test
    void logicallyDeletedActiveUserCannotLoginRefreshOrUseExistingAccessToken() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens tokens = login(firstInstance, user.username(), INITIAL_PASSWORD);
        String originalRefreshKey = REFRESH_PREFIX + refreshJti(tokens.refreshToken());

        assertEquals(1, jdbc().update("UPDATE user SET deleted = 1 WHERE id = ? AND status = 'ACTIVE'", user.id()));

        HttpResult loginAfterDelete = request(secondInstance, "POST", "/api/auth/login", null,
                Map.of("username", user.username(), "password", INITIAL_PASSWORD));
        assertEquals(409, loginAfterDelete.status());
        assertEquals(401, request(firstInstance, "GET", "/api/auth/me", tokens.accessToken(), null).status());

        HttpResult refreshAfterDelete = refresh(secondInstance, tokens.refreshToken());
        assertEquals(409, refreshAfterDelete.status());
        assertTrue(refreshAfterDelete.body().path("data").isNull()
                        || refreshAfterDelete.body().path("data").isMissingNode(),
                "Rejected refresh must not return newly issued tokens");
        assertTrue(Boolean.FALSE.equals(redis().hasKey(originalRefreshKey)),
                "Rejected refresh must consume the old refresh token without issuing another one");
    }

    @Test
    void productionInstancesSharePemVerificationAndRedisDenylist() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens tokens = login(firstInstance, user.username(), INITIAL_PASSWORD);

        assertEquals(200, request(secondInstance, "GET", "/api/auth/me", tokens.accessToken(), null).status());
        assertEquals(200, request(secondInstance, "POST", "/api/auth/logout", tokens.accessToken(),
                Map.of("refreshToken", tokens.refreshToken())).status());

        assertEquals(401, request(firstInstance, "GET", "/api/auth/me", tokens.accessToken(), null).status());
        assertEquals(409, refresh(firstInstance, tokens.refreshToken()).status());
    }

    @Test
    void restartedProductionInstanceKeepsOldTokenAndRefreshState() throws Exception {
        TestUser user = createUser(INITIAL_PASSWORD);
        Tokens beforeRestart = login(firstInstance, user.username(), INITIAL_PASSWORD);

        int stoppedPort = secondPort;
        close(secondInstance);
        secondInstance = null;
        awaitPortReleased(stoppedPort);
        secondInstance = startInstance();
        secondPort = port(secondInstance);

        assertEquals(200,
                request(secondInstance, "GET", "/api/auth/me", beforeRestart.accessToken(), null).status());
        HttpResult refreshed = refresh(secondInstance, beforeRestart.refreshToken());
        assertEquals(200, refreshed.status());
        Tokens afterRestart = tokens(refreshed);
        track(afterRestart);
        assertEquals(200, request(firstInstance, "GET", "/api/auth/me", afterRestart.accessToken(), null).status());
        assertEquals(409, refresh(firstInstance, beforeRestart.refreshToken()).status());
    }

    private ConfigurableApplicationContext startInstance() {
        String exclusions = String.join(",",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.redisson.spring.starter.RedissonAutoConfigurationV2");
        return new SpringApplicationBuilder(AuthTestApplication.class)
                .profiles("prod")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--spring.autoconfigure.exclude=" + exclusions,
                        "--spring.datasource.url=" + mysqlUrl(),
                        "--spring.datasource.username=" + property("learnhub.auth.it.mysql-username", "learnhub"),
                        "--spring.datasource.password=" + property("learnhub.auth.it.mysql-password", "learnhub"),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.datasource.hikari.maximum-pool-size=3",
                        "--spring.data.redis.host=" + property("learnhub.auth.it.redis-host", "localhost"),
                        "--spring.data.redis.port=" + property("learnhub.auth.it.redis-port", "16379"),
                        "--spring.data.redis.database=0",
                        "--learnhub.jwt.private-key-location=" + privateKey.toUri(),
                        "--learnhub.jwt.public-key-location=" + publicKey.toUri(),
                        "--learnhub.rabbit.enabled=false",
                        "--learnhub.storage.oss.enabled=false",
                        "--logging.level.com.learnhub=INFO");
    }

    private TestUser createUser(String password) {
        long id = positiveId();
        String username = "auth-it-" + UUID.randomUUID();
        TestUser user = new TestUser(id, username);
        testUsers.add(user);
        String encoded = firstInstance.getBean(PasswordEncoder.class).encode(password);
        jdbc().update("""
                INSERT INTO user (id, username, password_hash, nickname, status, token_version, deleted)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, 0)
                """, id, username, encoded, "认证集成测试");
        jdbc().update("INSERT INTO user_role (id, user_id, role_code) VALUES (?, ?, 'USER')", positiveId(), id);
        return user;
    }

    private Tokens login(ConfigurableApplicationContext context, String username, String password) throws Exception {
        HttpResult result = request(context, "POST", "/api/auth/login", null,
                Map.of("username", username, "password", password));
        assertEquals(200, result.status());
        Tokens tokens = tokens(result);
        track(tokens);
        return tokens;
    }

    private HttpResult refresh(ConfigurableApplicationContext context, String refreshToken) throws Exception {
        return request(context, "POST", "/api/auth/refresh", null, Map.of("refreshToken", refreshToken));
    }

    private HttpResult request(ConfigurableApplicationContext context, String method, String path,
                               String accessToken, Map<String, String> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port(context) + path))
                .timeout(HTTP_TIMEOUT);
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(
                    objectMapper().writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = httpClient.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = response.body().isBlank()
                ? objectMapper().createObjectNode()
                : objectMapper().readTree(response.body());
        return new HttpResult(response.statusCode(), json);
    }

    private Tokens tokens(HttpResult result) {
        JsonNode data = result.body().path("data");
        String accessToken = data.path("accessToken").textValue();
        String refreshToken = data.path("refreshToken").textValue();
        assertNotNull(accessToken, "Successful auth response did not include an access token");
        assertNotNull(refreshToken, "Successful auth response did not include a refresh token");
        return new Tokens(accessToken, refreshToken);
    }

    private void track(Tokens tokens) {
        redisKeys.add(DENY_PREFIX + accessJti(tokens.accessToken()));
        redisKeys.add(REFRESH_PREFIX + refreshJti(tokens.refreshToken()));
    }

    private String accessJti(String token) {
        return jwt().require(token, "access").getPayload().getId();
    }

    private String refreshJti(String token) {
        return jwt().require(token, "refresh").getPayload().getId();
    }

    private JwtTokenService jwt() {
        return firstInstance.getBean(JwtTokenService.class);
    }

    private JdbcTemplate jdbc() {
        ConfigurableApplicationContext context = firstInstance != null ? firstInstance : secondInstance;
        return context.getBean(JdbcTemplate.class);
    }

    private StringRedisTemplate redis() {
        ConfigurableApplicationContext context = firstInstance != null ? firstInstance : secondInstance;
        return context.getBean(StringRedisTemplate.class);
    }

    private ObjectMapper objectMapper() {
        return firstInstance.getBean(ObjectMapper.class);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private String property(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }

    private String mysqlUrl() {
        String explicit = System.getProperty("learnhub.auth.it.mysql-url");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String host = property("learnhub.auth.it.mysql-host", "localhost");
        String port = property("learnhub.auth.it.mysql-port", "13306");
        String database = property("learnhub.auth.it.mysql-database", "learnhub_auth_it");
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    private long positiveId() {
        long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private void writeTemporaryKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = writePem(temporaryDirectory.resolve("auth-it-private.pem"),
                "PRIVATE KEY", pair.getPrivate().getEncoded());
        publicKey = writePem(temporaryDirectory.resolve("auth-it-public.pem"),
                "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private Path writePem(Path path, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return Files.writeString(path, "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n");
    }

    private void close(ConfigurableApplicationContext context) {
        if (context != null) context.close();
    }

    private void closeAndCollect(ConfigurableApplicationContext context, List<Throwable> failures) {
        try {
            close(context);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private void awaitPortReleasedAndCollect(int port, List<Throwable> failures) {
        try {
            awaitPortReleased(port);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private void awaitPortReleased(int port) throws Exception {
        if (port < 0) return;
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            } catch (IOException released) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Test HTTP port was not released: " + port);
    }

    private void throwIfAny(List<Throwable> failures) {
        if (failures.isEmpty()) return;
        Throwable first = failures.get(0);
        failures.stream().skip(1).forEach(first::addSuppressed);
        if (first instanceof RuntimeException runtime) throw runtime;
        if (first instanceof Error error) throw error;
        throw new IllegalStateException("Authentication infrastructure cleanup failed", first);
    }

    private void throwIfAnyChecked(List<Throwable> failures) throws Exception {
        if (failures.isEmpty()) return;
        Throwable first = failures.get(0);
        failures.stream().skip(1).forEach(first::addSuppressed);
        if (first instanceof Exception exception) throw exception;
        if (first instanceof Error error) throw error;
        throw new IllegalStateException("Authentication infrastructure shutdown failed", first);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = {UserMapper.class, UserRoleMapper.class})
    @Import({AuthController.class, AuthService.class, RoleService.class, JwtTokenService.class,
            JwtAuthenticationFilter.class, SecurityConfig.class, GlobalExceptionHandler.class})
    static class AuthTestApplication {
        @Bean
        MediaAssetService mediaAssetService() {
            return mock(MediaAssetService.class);
        }
    }

    private record TestUser(long id, String username) {}

    private record Tokens(String accessToken, String refreshToken) {}

    private record HttpResult(int status, JsonNode body) {}
}
