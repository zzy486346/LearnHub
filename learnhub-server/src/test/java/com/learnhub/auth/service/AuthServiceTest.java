package com.learnhub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.auth.dto.ChangePasswordRequest;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.storage.MediaAssetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {
    @Test
    void currentUserReturnsNicknameFromDatabase() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setId(42L);
        user.setUsername("learner");
        user.setNickname("张同学");
        user.setAvatarMediaId(99L);
        user.setStatus("ACTIVE");
        when(userMapper.selectById(42L)).thenReturn(user);
        MediaAssetService mediaAssetService = mock(MediaAssetService.class);
        when(mediaAssetService.readyAccessUrl(99L)).thenReturn("https://signed.example/avatar.png");
        RoleService roleService = mock(RoleService.class);
        when(roleService.rolesForUser(42L)).thenReturn(List.of("ADMIN", "USER"));
        AuthService service = new AuthService(
                userMapper, mock(PasswordEncoder.class), mock(JwtTokenService.class), mediaAssetService, roleService);

        var response = service.currentUser(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.username()).isEqualTo("learner");
        assertThat(response.nickname()).isEqualTo("张同学");
        assertThat(response.avatarUrl()).isEqualTo("https://signed.example/avatar.png");
        assertThat(response.roles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void changesPasswordAndInvalidatesExistingTokenVersions() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = activeUser();
        user.setPasswordHash("old-hash");
        user.setTokenVersion(3);
        when(userMapper.selectById(42L)).thenReturn(user);
        when(encoder.matches("CurrentPass123", "old-hash")).thenReturn(true);
        when(encoder.matches("NewSecurePass456", "old-hash")).thenReturn(false);
        when(encoder.encode("NewSecurePass456")).thenReturn("new-hash");
        AuthService service = service(userMapper, encoder);

        service.changePassword(42L, new ChangePasswordRequest("CurrentPass123", "NewSecurePass456"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(4);
        verify(userMapper).updateById(user);
    }

    @Test
    void rejectsIncorrectCurrentPassword() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = activeUser();
        user.setPasswordHash("old-hash");
        when(userMapper.selectById(42L)).thenReturn(user);
        when(encoder.matches("WrongPass123", "old-hash")).thenReturn(false);
        AuthService service = service(userMapper, encoder);

        assertThatThrownBy(() -> service.changePassword(
                42L, new ChangePasswordRequest("WrongPass123", "NewSecurePass456")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CURRENT_PASSWORD_INCORRECT"));
    }

    private User activeUser() {
        User user = new User();
        user.setId(42L);
        user.setUsername("learner");
        user.setStatus("ACTIVE");
        user.setTokenVersion(0);
        return user;
    }

    private AuthService service(UserMapper userMapper, PasswordEncoder encoder) {
        return new AuthService(userMapper, encoder, mock(JwtTokenService.class),
                mock(MediaAssetService.class), mock(RoleService.class));
    }
}
