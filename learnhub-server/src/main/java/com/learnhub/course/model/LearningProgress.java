package com.learnhub.course.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("learning_progress")
public class LearningProgress {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long lessonId;
    private Integer positionSeconds;
    private Boolean completed;
    private LocalDateTime lastLearnedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public Integer getPositionSeconds() { return positionSeconds; }
    public void setPositionSeconds(Integer positionSeconds) { this.positionSeconds = positionSeconds; }
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }
    public LocalDateTime getLastLearnedAt() { return lastLearnedAt; }
    public void setLastLearnedAt(LocalDateTime lastLearnedAt) { this.lastLearnedAt = lastLearnedAt; }
}
