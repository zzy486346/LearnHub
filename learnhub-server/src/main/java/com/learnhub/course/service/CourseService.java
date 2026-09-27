package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import org.springframework.stereotype.Service;

@Service
public class CourseService {
    private final CourseMapper courseMapper;
    public CourseService(CourseMapper courseMapper) { this.courseMapper = courseMapper; }

    public Page<Course> list(long page, long size, Long categoryId, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<Course> query = new LambdaQueryWrapper<Course>().eq(Course::getStatus, "PUBLISHED")
                .eq(categoryId != null, Course::getCategoryId, categoryId)
                .like(keyword != null && !keyword.isBlank(), Course::getTitle, keyword)
                .orderByDesc(Course::getLikeCount).orderByDesc(Course::getId);
        return courseMapper.selectPage(Page.of(safePage, safeSize), query);
    }

    public Course detail(Long id) {
        Course course = courseMapper.selectOne(new LambdaQueryWrapper<Course>()
                .eq(Course::getId, id).eq(Course::getStatus, "PUBLISHED"));
        if (course == null) throw new BusinessException("课程不存在或未上架");
        return course;
    }
}
