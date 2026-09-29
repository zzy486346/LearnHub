package com.learnhub.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnhub.course.mapper.LearningProgressMapper;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.interaction.favorite.FavoriteRecord;
import com.learnhub.interaction.favorite.FavoriteRecordMapper;
import com.learnhub.interaction.qa.AnswerEntity;
import com.learnhub.interaction.qa.AnswerMapper;
import com.learnhub.interaction.qa.QuestionEntity;
import com.learnhub.interaction.qa.QuestionMapper;
import com.learnhub.profile.dto.ProfileOverviewResponse;
import com.learnhub.profile.model.ProfileRecentLearningRow;
import org.springframework.stereotype.Service;

@Service
public class ProfileOverviewService {
    private final LearningProgressMapper progressMapper;
    private final FavoriteRecordMapper favoriteMapper;
    private final QuestionMapper questionMapper;
    private final AnswerMapper answerMapper;

    public ProfileOverviewService(LearningProgressMapper progressMapper,
                                  FavoriteRecordMapper favoriteMapper,
                                  QuestionMapper questionMapper,
                                  AnswerMapper answerMapper) {
        this.progressMapper = progressMapper;
        this.favoriteMapper = favoriteMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
    }

    public ProfileOverviewResponse overview(Long userId) {
        long learningCourses = progressMapper.countLearningCourses(userId);
        long completedLessons = progressMapper.selectCount(new LambdaQueryWrapper<LearningProgress>()
                .eq(LearningProgress::getUserId, userId)
                .eq(LearningProgress::getCompleted, true));
        long favoriteCourses = favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteRecord>()
                .eq(FavoriteRecord::getUserId, userId)
                .eq(FavoriteRecord::getTargetType, "COURSE"));
        long questions = questionMapper.selectCount(new LambdaQueryWrapper<QuestionEntity>()
                .eq(QuestionEntity::getUserId, userId));
        long answers = answerMapper.selectCount(new LambdaQueryWrapper<AnswerEntity>()
                .eq(AnswerEntity::getUserId, userId));
        return new ProfileOverviewResponse(learningCourses, completedLessons, favoriteCourses,
                questions + answers, toRecentLearning(progressMapper.selectRecentLearning(userId)));
    }

    private ProfileOverviewResponse.RecentLearning toRecentLearning(ProfileRecentLearningRow row) {
        if (row == null) return null;
        int position = row.getPositionSeconds() == null ? 0 : Math.max(0, row.getPositionSeconds());
        int duration = row.getDurationSeconds() == null ? 0 : Math.max(0, row.getDurationSeconds());
        boolean completed = Boolean.TRUE.equals(row.getCompleted());
        int percentage = completed ? 100 : duration == 0 ? 0 : Math.min(99,
                Math.max(0, (int) Math.round(position * 100.0 / duration)));
        return new ProfileOverviewResponse.RecentLearning(row.getCourseId(), row.getCourseTitle(),
                row.getLessonId(), row.getLessonTitle(), position, duration, completed, percentage,
                row.getLastLearnedAt());
    }
}
