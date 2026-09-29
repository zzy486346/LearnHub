package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.storage.MediaAssetService;
import com.learnhub.storage.MediaResponses;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminCourseService {
    private final CourseMapper courseMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseLessonMapper lessonMapper;
    private final CourseService courseService;
    private final MediaAssetService mediaAssetService;

    public AdminCourseService(CourseMapper courseMapper, CourseChapterMapper chapterMapper,
                              CourseLessonMapper lessonMapper, CourseService courseService,
                              MediaAssetService mediaAssetService) {
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.courseService = courseService;
        this.mediaAssetService = mediaAssetService;
    }

    public List<Course> list() {
        return courseMapper.selectList(new LambdaQueryWrapper<Course>().orderByDesc(Course::getId));
    }

    public CourseDetailResponse detail(Long courseId) {
        return courseService.detailForAdmin(courseId);
    }

    @Transactional
    public Long createCourse(AdminCourseRequests.CreateCourse request) {
        Course course = new Course();
        course.setCategoryId(request.categoryId());
        course.setTitle(request.title().trim());
        course.setDescription(request.description() == null ? null : request.description().trim());
        course.setInstructor(request.instructor().trim());
        course.setPrice(request.price());
        course.setStatus(request.status() == null ? "DRAFT" : request.status());
        course.setLikeCount(0L);
        courseMapper.insert(course);
        return course.getId();
    }

    @Transactional
    public Long createChapter(Long courseId, AdminCourseRequests.CreateChapter request) {
        requireCourse(courseId);
        CourseChapter chapter = new CourseChapter();
        chapter.setCourseId(courseId);
        chapter.setTitle(request.title().trim());
        chapter.setSortOrder(request.sortOrder());
        chapterMapper.insert(chapter);
        return chapter.getId();
    }

    @Transactional
    public Long createLesson(Long chapterId, AdminCourseRequests.CreateLesson request) {
        if (chapterMapper.selectById(chapterId) == null) throw new BusinessException("章节不存在");
        CourseLesson lesson = new CourseLesson();
        lesson.setChapterId(chapterId);
        lesson.setTitle(request.title().trim());
        lesson.setDurationSeconds(request.durationSeconds());
        lesson.setFreePreview(request.freePreview());
        lesson.setSortOrder(request.sortOrder());
        lessonMapper.insert(lesson);
        return lesson.getId();
    }

    @Transactional
    public MediaResponses.Asset uploadLessonVideo(Long adminUserId, Long lessonId, MultipartFile file) {
        if (lessonMapper.selectById(lessonId) == null) throw new BusinessException("课时不存在");
        MediaResponses.Asset asset = mediaAssetService.uploadCourseVideo(adminUserId, lessonId, file);
        lessonMapper.update(null, new UpdateWrapper<CourseLesson>()
                .eq("id", lessonId)
                .set("media_asset_id", asset.id())
                .set("media_url", null));
        return asset;
    }

    private Course requireCourse(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) throw new BusinessException("课程不存在");
        return course;
    }
}
