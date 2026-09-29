package com.learnhub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.storage.MediaAssetService;
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
        AuthService service = new AuthService(
                userMapper, mock(PasswordEncoder.class), mock(JwtTokenService.class), mediaAssetService);

        var response = service.currentUser(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.username()).isEqualTo("learner");
        assertThat(response.nickname()).isEqualTo("张同学");
        assertThat(response.avatarUrl()).isEqualTo("https://signed.example/avatar.png");
    }
}
