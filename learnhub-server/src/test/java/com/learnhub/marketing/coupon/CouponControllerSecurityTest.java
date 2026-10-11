package com.learnhub.marketing.coupon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CouponController.class)
@Import(SecurityConfig.class)
class CouponControllerSecurityTest {
    private static final String CREATE_REQUEST = """
            {"name":"管理员券","stock":10,"endAt":"2099-01-01T00:00:00Z","seckill":false}
            """;

    @Autowired private MockMvc mvc;
    @MockBean private CouponService service;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void publicCanListCouponsButCannotCreateOne() throws Exception {
        mvc.perform(get("/api/coupons"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isUnauthorized());

        verify(service, never()).create(any());
    }

    @Test
    void regularUserCannotCreateCouponWithoutSideEffects() throws Exception {
        mvc.perform(post("/api/coupons")
                        .with(authentication(user("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any());
    }

    @Test
    void adminCanCreateCoupon() throws Exception {
        mvc.perform(post("/api/coupons")
                        .with(authentication(user("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isOk());

        verify(service).create(any(CouponRequests.Create.class));
    }

    private UsernamePasswordAuthenticationToken user(String authority) {
        return new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(7L, "operator"), null,
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
