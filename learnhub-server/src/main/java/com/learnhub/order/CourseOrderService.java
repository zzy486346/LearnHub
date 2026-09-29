package com.learnhub.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseOrderService {
    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    private static final BigDecimal ZERO_PAYMENT = new BigDecimal("0.00");

    private final CourseOrderMapper orderMapper;
    private final CourseMapper courseMapper;

    public CourseOrderService(CourseOrderMapper orderMapper, CourseMapper courseMapper) {
        this.orderMapper = orderMapper;
        this.courseMapper = courseMapper;
    }

    public boolean hasPurchased(Long userId, Long courseId) {
        if (userId == null) return false;
        return orderMapper.selectCount(new LambdaQueryWrapper<CourseOrder>()
                .eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getCourseId, courseId)
                .eq(CourseOrder::getStatus, PAID)) > 0;
    }

    @Transactional
    public CourseOrderResponse create(Long userId, Long courseId) {
        Course course = courseMapper.selectOne(new LambdaQueryWrapper<Course>()
                .eq(Course::getId, courseId)
                .eq(Course::getStatus, "PUBLISHED"));
        if (course == null) throw new BusinessException("COURSE_NOT_FOUND", "课程不存在或未上架");
        CourseOrder existing = findByUserAndCourse(userId, courseId);
        if (existing != null) return toResponse(existing);

        CourseOrder order = new CourseOrder();
        order.setOrderNo("LH" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        order.setUserId(userId);
        order.setCourseId(courseId);
        order.setCourseTitle(course.getTitle());
        order.setOriginalAmount(course.getPrice() == null ? BigDecimal.ZERO : course.getPrice());
        order.setStatus(PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(order.getCreatedAt());
        try {
            orderMapper.insert(order);
            return toResponse(order);
        } catch (DuplicateKeyException exception) {
            CourseOrder concurrent = findByUserAndCourse(userId, courseId);
            if (concurrent == null) throw exception;
            return toResponse(concurrent);
        }
    }

    @Transactional
    public CourseOrderResponse pay(Long userId, Long orderId) {
        CourseOrder order = orderMapper.selectOne(new LambdaQueryWrapper<CourseOrder>()
                .eq(CourseOrder::getId, orderId)
                .eq(CourseOrder::getUserId, userId));
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        if (PAID.equals(order.getStatus())) return toResponse(order);
        LocalDateTime paidAt = LocalDateTime.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<CourseOrder>()
                .eq(CourseOrder::getId, orderId)
                .eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getStatus, PENDING)
                .set(CourseOrder::getStatus, PAID)
                .set(CourseOrder::getPaidAmount, ZERO_PAYMENT)
                .set(CourseOrder::getUpdatedAt, paidAt)
                .set(CourseOrder::getPaidAt, paidAt));
        if (updated == 0) throw new BusinessException("ORDER_STATUS_INVALID", "订单状态已变化，请刷新后重试");
        order.setStatus(PAID);
        order.setPaidAmount(ZERO_PAYMENT);
        order.setUpdatedAt(paidAt);
        order.setPaidAt(paidAt);
        return toResponse(order);
    }

    private CourseOrder findByUserAndCourse(Long userId, Long courseId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<CourseOrder>()
                .eq(CourseOrder::getUserId, userId)
                .eq(CourseOrder::getCourseId, courseId));
    }

    private CourseOrderResponse toResponse(CourseOrder order) {
        return new CourseOrderResponse(String.valueOf(order.getId()), order.getOrderNo(),
                String.valueOf(order.getCourseId()), order.getCourseTitle(), order.getOriginalAmount(),
                order.getPaidAmount(), order.getStatus(), order.getCreatedAt(), order.getPaidAt());
    }
}
