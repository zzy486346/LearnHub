package com.learnhub.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/** 真实 Servlet 分派回归；随机回环端口，无业务中间件或模型调用。 */
@SpringBootTest(classes = AssistantAsyncHttpIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "learnhub.ai.mode=DISABLED"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(20)
class AssistantAsyncHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired FixtureApi api;
    @MockBean JwtTokenService tokens;
    @MockBean UserMapper users;
    @MockBean RoleService roles;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach @SuppressWarnings("unchecked") void validBearer() {
        api.pending.clear();
        Jws<Claims> token = mock(Jws.class);
        Claims claims = mock(Claims.class);
        when(tokens.require("async-fixture", "access")).thenReturn(token);
        when(token.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("7");
        when(claims.get("username", String.class)).thenReturn("async-fixture-user");
        when(claims.getId()).thenReturn("async-fixture-jti");
        User user = new User();
        user.setId(7L); user.setStatus("ACTIVE"); user.setDeleted(0); user.setTokenVersion(0);
        when(users.selectById(7L)).thenReturn(user);
        when(roles.rolesForUser(7L)).thenReturn(List.of("USER"));
    }

    @Test void validBearerReceivesCompletedAnswerWithoutExecutingControllerAgain() throws Exception {
        assertAsyncResponse(200, false, 200);
    }
    @Test void serviceUnavailableRemains503InsteadOfBeingReplacedBy401() throws Exception {
        assertAsyncResponse(503, false, 503);
    }
    @Test void logoutDuringAsyncWorkRejectsTheFinalResponse() throws Exception {
        assertAsyncResponse(200, true, 401);
    }
    @Test void anonymousRequestCannotEnterAsyncController() throws Exception {
        var request = HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(api.pending).isEmpty();
        verifyNoInteractions(tokens, users, roles);
    }
    private void assertAsyncResponse(int businessStatus, boolean revoked, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer async-fixture")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var pending = api.pending.poll(5, TimeUnit.SECONDS);
        assertThat(pending).isNotNull();
        if (revoked) when(tokens.isAccessDenied("async-fixture-jti")).thenReturn(true);
        pending.setResult(ResponseEntity.status(businessStatus).body(Map.of("status", "completed")));
        var completed = response.get(5, TimeUnit.SECONDS);
        assertThat(completed.statusCode()).isEqualTo(expectedStatus);
        if (!revoked) assertThat(completed.body()).contains("completed");
        assertThat(api.pending).isEmpty();
        verify(tokens, times(2)).require("async-fixture", "access");
    }
    private URI uri() { return URI.create("http://127.0.0.1:" + port + "/api/assistant/async-fixture"); }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, FixtureApi.class})
    static class App { }

    @RestController
    static class FixtureApi {
        final BlockingQueue<DeferredResult<ResponseEntity<?>>> pending = new LinkedBlockingQueue<>();
        @PostMapping("/api/assistant/async-fixture")
        DeferredResult<ResponseEntity<?>> answer() {
            var result = new DeferredResult<ResponseEntity<?>>(10_000L);
            pending.add(result);
            return result;
        }
    }
}
