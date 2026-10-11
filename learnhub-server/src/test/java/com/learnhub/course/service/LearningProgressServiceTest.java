package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.ProgressUpdateRequest;
import com.learnhub.course.mapper.LearningProgressMapper;
import com.learnhub.course.model.LearningProgress;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LearningProgressServiceTest {
    private final LearningProgressMapper mapper = mock(LearningProgressMapper.class);
    private final CourseService courses = mock(CourseService.class);
    private final LearningProgressService service = new LearningProgressService(mapper, courses);

    @Test
    void clampsPositionAndReturnsPersistedCompletionInsteadOfRequestFlag() {
        when(mapper.countLessonInCourse(2L, 1L)).thenReturn(1L);
        when(mapper.lessonDuration(2L)).thenReturn(60);
        LearningProgress persisted = new LearningProgress();
        persisted.setCompleted(true);
        when(mapper.selectOne(any())).thenReturn(persisted);
        assertThat(service.save(7L, 1L, new ProgressUpdateRequest(2L, 999, false)))
                .isSameAs(persisted);
        var captor = ArgumentCaptor.forClass(LearningProgress.class);
        verify(mapper).upsert(captor.capture());
        assertThat(captor.getValue().getPositionSeconds()).isEqualTo(60);
        assertThat(captor.getValue().getId()).isNotNull();
        verify(courses).requireLessonAccess(7L, 1L, 2L);
        verify(mapper, never()).insert(any(LearningProgress.class));
        verify(mapper, never()).updateById(any(LearningProgress.class));
    }

    @Test
    void rejectsForeignOrDeletedLessonWithoutWriting() {
        assertThatThrownBy(() -> service.save(7L, 1L, new ProgressUpdateRequest(2L, 1, false)))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).upsert(any());
    }

    @Test
    void rejectsLockedLessonWithoutWriting() {
        when(mapper.countLessonInCourse(2L, 1L)).thenReturn(1L);
        doThrow(new BusinessException("购买后学习")).when(courses).requireLessonAccess(7L, 1L, 2L);
        assertThatThrownBy(() -> service.save(7L, 1L, new ProgressUpdateRequest(2L, 1, false)))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).upsert(any());
    }
}
