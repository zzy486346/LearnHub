package com.learnhub.profile.dto;

import java.time.LocalDateTime;

public record ProfileOverviewResponse(long learningCourseCount,
                                      long completedLessonCount,
                                      long favoriteCourseCount,
                                      long qaContributionCount,
                                      RecentLearning recentLearning) {
    public record RecentLearning(Long courseId,
                                 String courseTitle,
                                 Long lessonId,
                                 String lessonTitle,
                                 int positionSeconds,
                                 int durationSeconds,
                                 boolean completed,
                                 int percentage,
                                 LocalDateTime lastLearnedAt) {}
}
