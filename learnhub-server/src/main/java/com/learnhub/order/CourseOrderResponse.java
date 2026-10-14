package com.learnhub.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record CourseOrderResponse(String id,
                                  String orderNo,
                                  String courseId,
                                  String courseTitle,
                                  BigDecimal originalAmount,
                                  @JsonSerialize(using = ToStringSerializer.class) Long couponClaimId,
                                  BigDecimal discountAmount,
                                  BigDecimal payableAmount,
                                  BigDecimal paidAmount,
                                  String paymentMode,
                                  String status,
                                  LocalDateTime createdAt,
                                  LocalDateTime paidAt,
                                  LocalDateTime expiresAt,
                                  LocalDateTime cancelledAt,
                                  LocalDateTime refundedAt) {}
