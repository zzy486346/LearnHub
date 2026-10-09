package com.learnhub.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.auth.dto.ChangePasswordRequest;
import com.learnhub.auth.dto.RefreshTokenRequest;
import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.service.AuthService;
import com.learnhub.auth.service.RoleService;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.config.JwtProperties;
import com.learnhub.storage.MediaAssetService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthTokenLifecycleTest {
    private final User user = activeUser();
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtTokenService tokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        when(userMapper.selectById(42L)).thenReturn(user);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = tokenServiceWithStatefulRedis();
        authService = new AuthService(userMapper, passwordEncoder, tokenService,
                mock(MediaAssetService.class), mock(RoleService.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logoutRevokesAccessAndRefreshTokensAndCanBeRepeated() throws Exception {
        TokenResponse tokens = tokenService.issue(user.getId(), user.getUsername(), user.getTokenVersion());
        String accessJti = tokenService.require(tokens.accessToken(), "access").getPayload().getId();
        assertThat(authenticate(tokens.accessToken())).isNotNull();

        authService.logout(tokens.accessToken(), tokens.refreshToken());
        authService.logout(tokens.accessToken(), tokens.refreshToken());

        assertThat(tokenService.isAccessDenied(accessJti)).isTrue();
        assertThat(authenticate(tokens.accessToken())).isNull();
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getMessage()).isEqualTo("刷新令牌已失效"));
    }

    @Test
    void refreshRotationRejectsReplayAndKeepsTheRotatedTokenUsable() {
        TokenResponse original = tokenService.issue(user.getId(), user.getUsername(), user.getTokenVersion());

        TokenResponse rotated = authService.refresh(new RefreshTokenRequest(original.refreshToken()));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(original.refreshToken())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getMessage()).isEqualTo("刷新令牌已失效"));
        assertThat(authService.refresh(new RefreshTokenRequest(rotated.refreshToken())).accessToken()).isNotBlank();
    }

    @Test
    void passwordChangeInvalidatesPreviouslyIssuedAccessAndRefreshTokens() throws Exception {
        user.setPasswordHash("old-hash");
        TokenResponse oldTokens = tokenService.issue(user.getId(), user.getUsername(), user.getTokenVersion());
        assertThat(authenticate(oldTokens.accessToken())).isNotNull();
        when(passwordEncoder.matches("CurrentPass123", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewSecurePass456", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewSecurePass456")).thenReturn("new-hash");

        authService.changePassword(42L, new ChangePasswordRequest("CurrentPass123", "NewSecurePass456"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(oldTokens.refreshToken())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getMessage()).isEqualTo("登录状态已失效，请重新登录"));

        assertThat(authenticate(oldTokens.accessToken())).isNull();
    }

    private Authentication authenticate(String accessToken) throws Exception {
        SecurityContextHolder.clearContext();
        RoleService roleService = mock(RoleService.class);
        when(roleService.rolesForUser(42L)).thenReturn(java.util.List.of("USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, roleService, userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private JwtTokenService tokenServiceWithStatefulRedis() {
        Map<String, String> state = new ConcurrentHashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            state.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(invocation -> state.get(invocation.getArgument(0)));
        when(values.getAndDelete(anyString())).thenAnswer(invocation -> state.remove(invocation.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(invocation -> state.containsKey(invocation.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(invocation -> state.remove(invocation.getArgument(0)) != null);

        JwtTokenService service = new JwtTokenService(new JwtProperties(), redis, Clock.systemUTC());
        service.initializeKeys();
        return service;
    }

    private static User activeUser() {
        User user = new User();
        user.setId(42L);
        user.setUsername("learner");
        user.setStatus("ACTIVE");
        user.setTokenVersion(0);
        return user;
    }
}
