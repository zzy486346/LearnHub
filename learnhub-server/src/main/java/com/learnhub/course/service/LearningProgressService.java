package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnhub.course.dto.ProgressUpdateRequest;
import com.learnhub.course.mapper.LearningProgressMapper;
import com.learnhub.course.model.LearningProgress;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningProgressService {
    private final LearningProgressMapper progressMapper;
    private final CourseService courseService;

    public LearningProgressService(LearningProgressMapper progressMapper, CourseService courseService) {
        this.progressMapper = progressMapper;
        this.courseService = courseService;
    }

    public List<LearningProgress> getByCourse(Long userId, Long courseId) {
        courseService.requirePublished(courseId);
        return progressMapper.selectByUserAndCourse(userId, courseId);
    }

    @Transactional
    public LearningProgress save(Long userId, Long courseId, ProgressUpdateRequest request) {
        courseService.requirePublished(courseId);
        if (progressMapper.countLessonInCourse(request.lessonId(), courseId) == 0) {
            throw new com.learnhub.common.exception.BusinessException("课时不属于该课程");
        }
        courseService.requireLessonAccess(userId, courseId, request.lessonId());
        LearningProgress progress = progressMapper.selectOne(query(userId, request.lessonId()));
        if (progress == null) {
            progress = new LearningProgress();
            progress.setUserId(userId);
        }
        progress.setLessonId(request.lessonId());
        progress.setPositionSeconds(request.positionSeconds());
        progress.setCompleted(request.completed());
        progress.setLastLearnedAt(LocalDateTime.now());
        if (progress.getId() == null) progressMapper.insert(progress); else progressMapper.updateById(progress);
        return progress;
    }

    private LambdaQueryWrapper<LearningProgress> query(Long userId, Long lessonId) {
        return new LambdaQueryWrapper<LearningProgress>()
                .eq(LearningProgress::getUserId, userId).eq(LearningProgress::getLessonId, lessonId);
    }
}
