package com.learnhub.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.marketing.coupon.CouponClaimEntity;
import com.learnhub.marketing.coupon.CouponClaimMapper;
import com.learnhub.marketing.coupon.CouponEntity;
import com.learnhub.marketing.coupon.CouponMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

class CourseOrderServiceTest {
    private final CourseOrderMapper orderMapper = mock(CourseOrderMapper.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final CouponClaimMapper claimMapper = mock(CouponClaimMapper.class);
    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final Environment environment = mock(Environment.class);
    private final CourseOrderService service = new CourseOrderService(orderMapper, courseMapper,
            claimMapper, couponMapper, "LOCAL_DEMO", Duration.ofMinutes(30), environment);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), CourseOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "claim-test"), CouponClaimEntity.class);
    }

    @Test
    void createsPendingOrderWithCoursePriceSnapshot() {
        Course course = new Course();
        course.setId(1001L);
        course.setTitle("大模型应用开发入门");
        course.setPrice(new BigDecimal("199.00"));
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(course);
        when(orderMapper.selectUserCourse(7L, 1001L)).thenReturn(null);
        doAnswer(invocation -> {
            CourseOrder order = invocation.getArgument(0);
            order.setId(9001L);
            return 1;
        }).when(orderMapper).insert(any(CourseOrder.class));

        CourseOrderResponse result = service.create(7L, 1001L);

        assertThat(result.id()).isEqualTo("9001");
        assertThat(result.courseTitle()).isEqualTo("大模型应用开发入门");
        assertThat(result.originalAmount()).isEqualByComparingTo("199.00");
        assertThat(result.paidAmount()).isNull();
        assertThat(result.status()).isEqualTo(CourseOrderService.PENDING);
    }

    @Test
    void paysOrderWithActualPayableAmount() {
        CourseOrder order = new CourseOrder();
        order.setId(9001L);
        order.setUserId(7L);
        order.setCourseId(1001L);
        order.setCourseTitle("大模型应用开发入门");
        order.setOriginalAmount(new BigDecimal("199.00"));
        order.setDiscountAmount(new BigDecimal("30.00"));
        order.setPayableAmount(new BigDecimal("169.00"));
        order.setPaymentMode(CourseOrderService.LOCAL_DEMO);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(20));
        order.setStatus(CourseOrderService.PENDING);
        when(orderMapper.selectById(9001L)).thenReturn(order);
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(new Course());
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.pay(7L, 9001L);

        assertThat(result.status()).isEqualTo(CourseOrderService.PAID);
        assertThat(result.payableAmount()).isEqualByComparingTo("169.00");
        assertThat(result.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(result.paidAt()).isNotNull();
    }

    @Test
    void rejectsOrderThatDoesNotBelongToCurrentUser() {
        when(orderMapper.selectById(9001L)).thenReturn(null);

        assertThatThrownBy(() -> service.pay(8L, 9001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_FOUND"));
    }

    @Test
    void rejectsPendingPaymentAfterCourseOfflineWithoutChangingOrder() {
        CourseOrder order = order(9001L, 7L, 1001L, "下架课程", CourseOrderService.PENDING);
        when(orderMapper.selectById(9001L)).thenReturn(order);
        assertThatThrownBy(() -> service.pay(7L, 9001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_NOT_FOUND"));
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        assertThat(order.getStatus()).isEqualTo(CourseOrderService.PENDING);
    }

    @Test
    void alreadyPaidOfflineOrderStillReturnsPaidIdempotently() {
        CourseOrder order = order(9001L, 7L, 1001L, "下架课程", CourseOrderService.PAID);
        when(orderMapper.selectById(9001L)).thenReturn(order);
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(new Course());
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);
        assertThat(service.pay(7L, 9001L).status()).isEqualTo(CourseOrderService.PAID);
        verify(courseMapper, never()).selectPublishedForPurchase(any());
    }

    @Test
    void reservesOwnedCouponAndCalculatesPayableAmount() {
        Course course = course(1001L, "系统课", "199.00");
        CouponClaimEntity claim = claim(3001L, 7L, 501L, "AVAILABLE");
        CouponEntity coupon = coupon(501L, "30.00", "100.00");
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(course);
        when(orderMapper.selectUserCourse(7L, 1001L)).thenReturn(null);
        when(claimMapper.selectOwnedForUpdate(3001L, 7L)).thenReturn(claim);
        when(couponMapper.selectForUpdate(501L)).thenReturn(coupon);
        when(claimMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.create(7L, 1001L, 3001L);

        assertThat(result.couponClaimId()).isEqualTo(3001L);
        assertThat(result.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(result.payableAmount()).isEqualByComparingTo("169.00");
        assertThat(result.paymentMode()).isEqualTo(CourseOrderService.LOCAL_DEMO);
    }

    @Test
    void cancelReleasesReservedCoupon() {
        CourseOrder order = order(9001L, 7L, 1001L, "系统课", CourseOrderService.PENDING);
        order.setCouponClaimId(3001L);
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(claimMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.cancel(7L, 9001L);

        assertThat(result.status()).isEqualTo(CourseOrderService.CANCELLED);
        assertThat(result.cancelledAt()).isNotNull();
        verify(claimMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void refundRevokesPurchaseAndReturnsUsedCoupon() {
        CourseOrder order = order(9001L, 7L, 1001L, "系统课", CourseOrderService.PAID);
        order.setCouponClaimId(3001L);
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(claimMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.refund(7L, 9001L);

        assertThat(result.status()).isEqualTo(CourseOrderService.REFUNDED);
        assertThat(result.refundedAt()).isNotNull();
        verify(claimMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void productionProfileRejectsLocalDemoPaymentEvenWhenMisconfigured() {
        Environment production = mock(Environment.class);
        when(production.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        CourseOrderService productionService = new CourseOrderService(orderMapper, courseMapper,
                claimMapper, couponMapper, "LOCAL_DEMO", Duration.ofMinutes(30), production);
        CourseOrder order = order(9001L, 7L, 1001L, "系统课", CourseOrderService.PENDING);
        when(orderMapper.selectById(9001L)).thenReturn(order);
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(new Course());
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);

        assertThatThrownBy(() -> productionService.pay(7L, 9001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_UNAVAILABLE"));
    }

    @Test
    void expiredPaymentClosesOrderAndReturnsReservedCoupon() {
        CourseOrder order = order(9001L, 7L, 1001L, "系统课", CourseOrderService.PENDING);
        order.setCouponClaimId(3001L);
        order.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(orderMapper.selectById(9001L)).thenReturn(order);
        when(courseMapper.selectPublishedForPurchase(1001L)).thenReturn(new Course());
        when(orderMapper.selectOwnedForUpdate(9001L, 7L)).thenReturn(order);
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(claimMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.pay(7L, 9001L);

        assertThat(result.status()).isEqualTo(CourseOrderService.CLOSED);
        verify(claimMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void rejectsNewOrderWhenCourseIsNotPublished() {
        assertThatThrownBy(() -> service.create(7L, 1001L)).isInstanceOf(BusinessException.class);
        verify(orderMapper, never()).insert(any(CourseOrder.class));
    }

    @Test
    void listsOnlyCurrentUsersOrdersFromMapperResult() {
        CourseOrder newer = order(9002L, 7L, 1002L, "Spring Boot 3 实战", CourseOrderService.PAID);
        CourseOrder older = order(9001L, 7L, 1001L, "大模型应用开发入门", CourseOrderService.PENDING);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(newer, older));

        List<CourseOrderResponse> result = service.listMine(7L);

        assertThat(result).extracting(CourseOrderResponse::id).containsExactly("9002", "9001");
        assertThat(result).extracting(CourseOrderResponse::courseTitle)
                .containsExactly("Spring Boot 3 实战", "大模型应用开发入门");
    }

    private CourseOrder order(Long id, Long userId, Long courseId, String title, String status) {
        CourseOrder order = new CourseOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setCourseId(courseId);
        order.setCourseTitle(title);
        order.setOriginalAmount(new BigDecimal("99.00"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayableAmount(new BigDecimal("99.00"));
        order.setPaymentMode(CourseOrderService.LOCAL_DEMO);
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.now());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return order;
    }

    private Course course(Long id, String title, String price) {
        Course course = new Course();
        course.setId(id);
        course.setTitle(title);
        course.setPrice(new BigDecimal(price));
        return course;
    }

    private CouponClaimEntity claim(Long id, Long userId, Long couponId, String status) {
        CouponClaimEntity claim = new CouponClaimEntity();
        claim.setId(id);
        claim.setUserId(userId);
        claim.setCouponId(couponId);
        claim.setStatus(status);
        return claim;
    }

    private CouponEntity coupon(Long id, String discount, String threshold) {
        CouponEntity coupon = new CouponEntity();
        coupon.setId(id);
        coupon.setDiscountAmount(new BigDecimal(discount));
        coupon.setThresholdAmount(new BigDecimal(threshold));
        coupon.setUseStartAt(LocalDateTime.now().minusMinutes(1));
        coupon.setUseEndAt(LocalDateTime.now().plusDays(1));
        return coupon;
    }
}
