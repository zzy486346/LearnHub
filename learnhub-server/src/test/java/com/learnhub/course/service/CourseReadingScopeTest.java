package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learnhub.course.mapper.*;
import com.learnhub.course.model.*;
import com.learnhub.interaction.like.LikeService;
import com.learnhub.order.CourseOrderService;
import com.learnhub.storage.MediaAssetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;

class CourseReadingScopeTest {
    final CourseMapper courses = mock(CourseMapper.class);
    final CourseChapterMapper chapters = mock(CourseChapterMapper.class);
    final CourseLessonMapper lessons = mock(CourseLessonMapper.class);
    final CourseOrderService orders = mock(CourseOrderService.class);
    final MediaAssetService media = mock(MediaAssetService.class);
    final LikeService likes = mock(LikeService.class);
    final CourseService service = new CourseService(courses, chapters, lessons, media, likes, orders);

    void available(String status) {
        Course course = new Course(); course.setId(1L); course.setStatus(status);
        when(courses.selectById(1L)).thenReturn(course);
        CourseChapter chapter = new CourseChapter(); chapter.setId(10L);
        when(chapters.selectList(any())).thenReturn(List.of(chapter));
        CourseLesson lesson = new CourseLesson(); lesson.setId(20L);
        when(lessons.selectList(any())).thenReturn(List.of(lesson));
        // 初始化 MyBatis 元数据以便在独立单元测试中检查 Lambda SQL。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CourseLesson.class);
    }

    @Test void previewOnlyBeforePurchaseAndNoMediaWork() {
        available("PUBLISHED");
        assertThat(service.readableLessonIds(7L, 1L)).containsExactly(20L);
        ArgumentCaptor<LambdaQueryWrapper<CourseLesson>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lessons).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("free_preview");
        verifyNoInteractions(media, likes);
    }

    @Test void purchasedOfflineCourseIncludesNonPreviewLessons() {
        available("OFFLINE"); when(orders.hasPurchased(7L, 1L)).thenReturn(true);
        assertThat(service.readableLessonIds(7L, 1L)).containsExactly(20L);
        ArgumentCaptor<LambdaQueryWrapper<CourseLesson>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lessons).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).doesNotContain("free_preview");
    }

    @Test void unavailableCourseAndAnonymousFailBeforeLessonQuery() {
        available("OFFLINE");
        assertThatThrownBy(() -> service.readableLessonIds(7L, 1L)).hasMessage("课程不存在或未上架");
        assertThatThrownBy(() -> service.readableLessonIds(null, 1L)).hasMessage("请先登录");
        verify(lessons, never()).selectList(any());
    }
}
