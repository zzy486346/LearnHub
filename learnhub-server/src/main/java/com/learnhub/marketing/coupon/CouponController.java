package com.learnhub.marketing.coupon;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.auth.security.LearnHubPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {
    private final CouponService service;

    public CouponController(CouponService service) { this.service = service; }

    @PostMapping
    public ApiResponse<Coupon> create(@Valid @RequestBody CouponRequests.Create request) {
        return ApiResponse.success(service.create(request));
    }

    @GetMapping
    public ApiResponse<List<Coupon>> list() { return ApiResponse.success(service.list()); }

    @GetMapping("/me")
    public ApiResponse<List<MyCoupon>> mine(@AuthenticationPrincipal LearnHubPrincipal principal) {
        return ApiResponse.success(service.mine(principal.userId()));
    }

    @PostMapping("/{couponId}/claim")
    public ApiResponse<CouponClaim> claim(@AuthenticationPrincipal LearnHubPrincipal principal, @PathVariable Long couponId) {
        return ApiResponse.success(service.claim(couponId, principal.userId()));
    }

    @PostMapping("/{couponId}/seckill")
    public ApiResponse<CouponClaim> seckill(@AuthenticationPrincipal LearnHubPrincipal principal, @PathVariable Long couponId) {
        return ApiResponse.success(service.seckill(couponId, principal.userId()));
    }

    @GetMapping("/{couponId}/claims/me")
    public ApiResponse<CouponClaim> status(@AuthenticationPrincipal LearnHubPrincipal principal,
                                           @PathVariable Long couponId) {
        return ApiResponse.success(service.status(couponId, principal.userId()));
    }
}
