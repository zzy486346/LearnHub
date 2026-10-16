package com.learnhub.assistant.runtime;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.common.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class AssistantSessionVerifier {
    private final JwtTokenService tokens;
    private final UserMapper users;
    public AssistantSessionVerifier(JwtTokenService tokens, UserMapper users) {
        this.tokens = tokens; this.users = users;
    }

    /** 仅在内存闭包中保留本次 access token，不写日志或发送供应商。 */
    public Runnable checkpoint(Long userId, String authorization) {
        if (userId == null || authorization == null || !authorization.startsWith("Bearer ")) throw rejected();
        String access = authorization.substring(7);
        return () -> {
            try {
                var token = tokens.require(access, "access");
                var user = users.selectById(userId);
                if (user == null || !userId.toString().equals(token.getPayload().getSubject())
                        || !"ACTIVE".equals(user.getStatus()) || Integer.valueOf(1).equals(user.getDeleted())
                        || tokens.tokenVersion(token) != (user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                        || tokens.isAccessDenied(token.getPayload().getId())) throw rejected();
            } catch (RuntimeException error) { throw rejected(); }
        };
    }

    private static BusinessException rejected() {
        return new BusinessException("ASSISTANT_SESSION_EXPIRED", "登录状态已失效，请重新登录");
    }
}
