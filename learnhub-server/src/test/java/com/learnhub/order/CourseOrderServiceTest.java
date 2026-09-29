package com.learnhub.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import java.math.BigDecimal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CourseOrderServiceTest {
    private final CourseOrderMapper orderMapper = mock(CourseOrderMapper.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final CourseOrderService service = new CourseOrderService(orderMapper, courseMapper);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), CourseOrder.class);
    }

    @Test
    void createsPendingOrderWithCoursePriceSnapshot() {
        Course course = new Course();
        course.setId(1001L);
        course.setTitle("大模型应用开发入门");
        course.setPrice(new BigDecimal("199.00"));
        when(courseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(course);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
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
    void paysOrderWithZeroAmount() {
        CourseOrder order = new CourseOrder();
        order.setId(9001L);
        order.setUserId(7L);
        order.setCourseId(1001L);
        order.setCourseTitle("大模型应用开发入门");
        order.setOriginalAmount(new BigDecimal("199.00"));
        order.setStatus(CourseOrderService.PENDING);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        CourseOrderResponse result = service.pay(7L, 9001L);

        assertThat(result.status()).isEqualTo(CourseOrderService.PAID);
        assertThat(result.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(result.paidAt()).isNotNull();
    }

    @Test
    void rejectsOrderThatDoesNotBelongToCurrentUser() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.pay(8L, 9001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_FOUND"));
    }
}
