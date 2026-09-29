package com.learnhub.profile.model;

import java.time.LocalDateTime;

public class ProfileRecentLearningRow {
    private Long courseId;
    private String courseTitle;
    private Long lessonId;
    private String lessonTitle;
    private Integer positionSeconds;
    private Integer durationSeconds;
    private Boolean completed;
    private LocalDateTime lastLearnedAt;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getCourseTitle() { return courseTitle; }
    public void setCourseTitle(String courseTitle) { this.courseTitle = courseTitle; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public String getLessonTitle() { return lessonTitle; }
    public void setLessonTitle(String lessonTitle) { this.lessonTitle = lessonTitle; }
    public Integer getPositionSeconds() { return positionSeconds; }
    public void setPositionSeconds(Integer positionSeconds) { this.positionSeconds = positionSeconds; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }
    public LocalDateTime getLastLearnedAt() { return lastLearnedAt; }
    public void setLastLearnedAt(LocalDateTime lastLearnedAt) { this.lastLearnedAt = lastLearnedAt; }
}
