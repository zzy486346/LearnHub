package com.learnhub.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.course.mapper.LearningProgressMapper;
import com.learnhub.interaction.favorite.FavoriteRecordMapper;
import com.learnhub.interaction.qa.AnswerMapper;
import com.learnhub.interaction.qa.QuestionMapper;
import com.learnhub.profile.model.ProfileRecentLearningRow;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ProfileOverviewServiceTest {
    @Test
    void returnsRealCountsAndRecentLearningPercentage() {
        LearningProgressMapper progressMapper = mock(LearningProgressMapper.class);
        FavoriteRecordMapper favoriteMapper = mock(FavoriteRecordMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        AnswerMapper answerMapper = mock(AnswerMapper.class);
        when(progressMapper.countLearningCourses(7L)).thenReturn(2L);
        when(progressMapper.selectCount(any())).thenReturn(4L);
        when(favoriteMapper.selectCount(any())).thenReturn(3L);
        when(questionMapper.selectCount(any())).thenReturn(5L);
        when(answerMapper.selectCount(any())).thenReturn(6L);
        ProfileRecentLearningRow row = new ProfileRecentLearningRow();
        row.setCourseId(1001L);
        row.setCourseTitle("Spring Boot 3 实战");
        row.setLessonId(3003L);
        row.setLessonTitle("设计 REST API");
        row.setPositionSeconds(30);
        row.setDurationSeconds(60);
        row.setCompleted(false);
        row.setLastLearnedAt(LocalDateTime.of(2026, 9, 29, 9, 0));
        when(progressMapper.selectRecentLearning(7L)).thenReturn(row);

        var response = new ProfileOverviewService(progressMapper, favoriteMapper, questionMapper, answerMapper)
                .overview(7L);

        assertThat(response.learningCourseCount()).isEqualTo(2);
        assertThat(response.completedLessonCount()).isEqualTo(4);
        assertThat(response.favoriteCourseCount()).isEqualTo(3);
        assertThat(response.qaContributionCount()).isEqualTo(11);
        assertThat(response.recentLearning().percentage()).isEqualTo(50);
        assertThat(response.recentLearning().lessonTitle()).isEqualTo("设计 REST API");
    }

    @Test
    void returnsEmptyRecentLearningForNewUser() {
        LearningProgressMapper progressMapper = mock(LearningProgressMapper.class);
        FavoriteRecordMapper favoriteMapper = mock(FavoriteRecordMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        AnswerMapper answerMapper = mock(AnswerMapper.class);

        var response = new ProfileOverviewService(progressMapper, favoriteMapper, questionMapper, answerMapper)
                .overview(9L);

        assertThat(response.recentLearning()).isNull();
        assertThat(response.learningCourseCount()).isZero();
        assertThat(response.qaContributionCount()).isZero();
    }
}
