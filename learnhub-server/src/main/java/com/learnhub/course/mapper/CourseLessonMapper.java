package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.CourseLesson;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseLessonMapper extends BaseMapper<CourseLesson> {}
