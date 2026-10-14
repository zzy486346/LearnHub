package com.learnhub.order;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class CourseOrderController {
    private final CourseOrderService service;

    public CourseOrderController(CourseOrderService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public ApiResponse<List<CourseOrderResponse>> mine(@AuthenticationPrincipal LearnHubPrincipal principal) {
        return ApiResponse.success(service.listMine(principal.userId()));
    }

    @PostMapping("/courses/{courseId}")
    public ApiResponse<CourseOrderResponse> create(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                    @PathVariable Long courseId,
                                                    @RequestBody(required = false) CourseOrderRequests.Create request) {
        return ApiResponse.success(service.create(principal.userId(), courseId,
                request == null ? null : request.couponClaimId()));
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<CourseOrderResponse> pay(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                 @PathVariable Long orderId) {
        return ApiResponse.success(service.pay(principal.userId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<CourseOrderResponse> cancel(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                    @PathVariable Long orderId) {
        return ApiResponse.success(service.cancel(principal.userId(), orderId));
    }

    @PostMapping("/{orderId}/refund")
    public ApiResponse<CourseOrderResponse> refund(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                    @PathVariable Long orderId) {
        return ApiResponse.success(service.refund(principal.userId(), orderId));
    }
}
