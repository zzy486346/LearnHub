package com.learnhub.course.controller;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.storage.MediaAssetService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseCoverController {
    private final CourseMapper courseMapper;
    private final MediaAssetService mediaAssetService;

    public CourseCoverController(CourseMapper courseMapper, MediaAssetService mediaAssetService) {
        this.courseMapper = courseMapper;
        this.mediaAssetService = mediaAssetService;
    }

    @GetMapping("/{courseId}/cover")
    public ResponseEntity<Void> cover(@PathVariable Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) throw new BusinessException("COURSE_NOT_FOUND", "课程不存在");
        String url = mediaAssetService.readyAccessUrl(course.getCoverMediaId());
        if (url == null || url.isBlank()) {
            throw new BusinessException("COURSE_COVER_NOT_READY", "课程封面不存在或尚未就绪");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(url))
                .build();
    }
}
