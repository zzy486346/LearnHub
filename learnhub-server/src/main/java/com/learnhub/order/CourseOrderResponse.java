package com.learnhub.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CourseOrderResponse(String id,
                                  String orderNo,
                                  String courseId,
                                  String courseTitle,
                                  BigDecimal originalAmount,
                                  BigDecimal paidAmount,
                                  String status,
                                  LocalDateTime createdAt,
                                  LocalDateTime paidAt) {}
