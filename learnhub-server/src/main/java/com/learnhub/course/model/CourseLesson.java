package com.learnhub.course.model;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("course_lesson")
public class CourseLesson {
    private Long id;
    private Long chapterId;
    private String title;
    private String mediaUrl;
    private Long mediaAssetId;
    private Integer durationSeconds;
    private Boolean freePreview;
    private Integer sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }
    public Long getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(Long mediaAssetId) { this.mediaAssetId = mediaAssetId; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public Boolean getFreePreview() { return freePreview; }
    public void setFreePreview(Boolean freePreview) { this.freePreview = freePreview; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
