package com.learnhub.auth.security;

import com.learnhub.auth.service.RoleService;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokenService;
    private final RoleService roleService;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtTokenService tokenService, RoleService roleService, UserMapper userMapper) {
        this.tokenService = tokenService;
        this.roleService = roleService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Jws<Claims> token = tokenService.require(authorization.substring(7), "access");
                Claims claims = token.getPayload();
                User user = userMapper.selectById(Long.valueOf(claims.getSubject()));
                int currentVersion = user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
                if (user != null && "ACTIVE".equals(user.getStatus())
                        && tokenService.tokenVersion(token) == currentVersion
                        && !tokenService.isAccessDenied(claims.getId())) {
                    LearnHubPrincipal principal = new LearnHubPrincipal(
                            Long.valueOf(claims.getSubject()), claims.get("username", String.class));
                    List<SimpleGrantedAuthority> authorities = roleService.rolesForUser(principal.userId()).stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, authorities));
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
