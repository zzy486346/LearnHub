package com.learnhub.order;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class CourseOrderController {
    private final CourseOrderService service;

    public CourseOrderController(CourseOrderService service) {
        this.service = service;
    }

    @PostMapping("/courses/{courseId}")
    public ApiResponse<CourseOrderResponse> create(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                    @PathVariable Long courseId) {
        return ApiResponse.success(service.create(principal.userId(), courseId));
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<CourseOrderResponse> pay(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                 @PathVariable Long orderId) {
        return ApiResponse.success(service.pay(principal.userId(), orderId));
    }
}
