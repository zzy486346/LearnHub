package com.learnhub.course.model;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("course_tag")
public class CourseTagBinding {
    private Long courseId;
    private Long tagId;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
}
