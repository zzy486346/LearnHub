package com.learnhub.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnhub.auth.dto.LoginRequest;
import com.learnhub.auth.dto.ChangePasswordRequest;
import com.learnhub.auth.dto.CurrentUserResponse;
import com.learnhub.auth.dto.RefreshTokenRequest;
import com.learnhub.auth.dto.RegisterRequest;
import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.storage.MediaAssetService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final MediaAssetService mediaAssetService;
    private final RoleService roleService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenService tokenService,
                       MediaAssetService mediaAssetService, RoleService roleService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.mediaAssetService = mediaAssetService;
        this.roleService = roleService;
    }

    @Transactional
    public Long register(RegisterRequest request) {
        if (findByUsername(request.username()) != null) throw new BusinessException("用户名已存在");
        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname() == null || request.nickname().isBlank() ? request.username() : request.nickname());
        user.setStatus("ACTIVE");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }

    public TokenResponse login(LoginRequest request) {
        User user = findByUsername(request.username());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        return tokenService.issue(user.getId(), user.getUsername(), tokenVersion(user));
    }

    public TokenResponse refresh(RefreshTokenRequest request) {
        try {
            Jws<Claims> refresh = tokenService.require(request.refreshToken(), "refresh");
            if (!tokenService.consumeRefresh(refresh)) throw new BusinessException("刷新令牌已失效");
            Long userId = Long.valueOf(refresh.getPayload().getSubject());
            User user = userMapper.selectById(userId);
            if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BusinessException("用户不可用");
            if (tokenService.tokenVersion(refresh) != tokenVersion(user)) {
                throw new BusinessException("登录状态已失效，请重新登录");
            }
            return tokenService.issue(user.getId(), user.getUsername(), tokenVersion(user));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException("刷新令牌无效");
        }
    }

    public CurrentUserResponse currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        String avatarUrl = mediaAssetService.readyAccessUrl(user.getAvatarMediaId());
        if (avatarUrl == null) avatarUrl = user.getAvatarUrl();
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getNickname(), avatarUrl,
                roleService.rolesForUser(userId));
    }

    @Transactional
    public CurrentUserResponse updateAvatar(Long userId, MultipartFile file) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        var asset = mediaAssetService.uploadAvatar(userId, file);
        user.setAvatarMediaId(asset.id());
        user.setAvatarUrl(null);
        userMapper.updateById(user);
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getNickname(), asset.url(),
                roleService.rolesForUser(userId));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("CURRENT_PASSWORD_INCORRECT", "当前密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_UNCHANGED", "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion(tokenVersion(user) + 1);
        userMapper.updateById(user);
    }

    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            try { tokenService.denyAccess(tokenService.require(accessToken, "access")); } catch (JwtException ignored) { }
        }
        if (refreshToken != null) {
            try { tokenService.revokeRefresh(tokenService.require(refreshToken, "refresh").getPayload().getId()); }
            catch (JwtException ignored) { }
        }
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private int tokenVersion(User user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }
}
