package com.learnhub.interaction.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class LikeServiceTest {
    @Test
    void courseLikeUpdatesLiveCountAndPersistsItForCourseLists() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        CourseMapper courseMapper = mock(CourseMapper.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        LikeService service = new LikeService(redisProvider, rabbitProvider, courseMapper, false);

        LikeResult result = service.setLike(7L, LikeTargetType.COURSE, 1002L, true);

        assertThat(result.liked()).isTrue();
        assertThat(result.count()).isEqualTo(1L);
        assertThat(service.count(LikeTargetType.COURSE, 1002L)).isEqualTo(1L);
        ArgumentCaptor<Course> course = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(course.capture());
        assertThat(course.getValue().getId()).isEqualTo(1002L);
        assertThat(course.getValue().getLikeCount()).isEqualTo(1L);
    }
}
