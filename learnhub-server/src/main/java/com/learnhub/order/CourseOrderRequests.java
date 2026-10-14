package com.learnhub.order;

public final class CourseOrderRequests {
    private CourseOrderRequests() {}

    public record Create(Long couponClaimId) {}
}
