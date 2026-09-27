package com.learnhub.marketing.coupon;

import com.learnhub.auth.security.LearnHubPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {
    private final CouponService service;

    public CouponController(CouponService service) { this.service = service; }

    @PostMapping
    public Coupon create(@Valid @RequestBody CouponRequests.Create request) { return service.create(request); }

    @GetMapping
    public List<Coupon> list() { return service.list(); }

    @PostMapping("/{couponId}/claim")
    public CouponClaim claim(@AuthenticationPrincipal LearnHubPrincipal principal, @PathVariable Long couponId) {
        return service.claim(couponId, principal.userId());
    }

    @PostMapping("/{couponId}/seckill")
    public CouponClaim seckill(@AuthenticationPrincipal LearnHubPrincipal principal, @PathVariable Long couponId) {
        return service.seckill(couponId, principal.userId());
    }

    @GetMapping("/{couponId}/claims/me")
    public ResponseEntity<CouponClaim> status(@AuthenticationPrincipal LearnHubPrincipal principal,
                                              @PathVariable Long couponId) {
        return ResponseEntity.ofNullable(service.status(couponId, principal.userId()));
    }
}
