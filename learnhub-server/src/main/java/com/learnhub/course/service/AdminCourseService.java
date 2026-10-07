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
import com.learnhub.search.CourseIndexTaskService;
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
    private final CourseIndexTaskService indexTaskService;

    public AdminCourseService(CourseMapper courseMapper, CourseChapterMapper chapterMapper,
                              CourseLessonMapper lessonMapper, CourseService courseService,
                              MediaAssetService mediaAssetService,
                              CourseIndexTaskService indexTaskService) {
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.courseService = courseService;
        this.mediaAssetService = mediaAssetService;
        this.indexTaskService = indexTaskService;
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
        if ("PUBLISHED".equals(course.getStatus())) {
            courseMapper.update(null, new UpdateWrapper<Course>()
                    .eq("id", course.getId())
                    .set("published_at", java.time.LocalDateTime.now()));
        }
        indexTaskService.enqueue(course.getId());
        return course.getId();
    }

    @Transactional
    public void updateCourse(Long courseId, AdminCourseRequests.UpdateCourse request) {
        requireCourse(courseId);
        UpdateWrapper<Course> update = new UpdateWrapper<Course>().eq("id", courseId);
        boolean changed = false;
        if (request.categoryId() != null) { update.set("category_id", request.categoryId()); changed = true; }
        if (request.title() != null) { update.set("title", requiredTrim(request.title(), "课程标题不能为空")); changed = true; }
        if (request.subtitle() != null) { update.set("subtitle", nullableTrim(request.subtitle())); changed = true; }
        if (request.coverUrl() != null) { update.set("cover_url", nullableTrim(request.coverUrl())); changed = true; }
        if (request.description() != null) { update.set("description", nullableTrim(request.description())); changed = true; }
        if (request.instructor() != null) { update.set("instructor", requiredTrim(request.instructor(), "讲师不能为空")); changed = true; }
        if (request.price() != null) { update.set("price", request.price()); changed = true; }
        if (!changed) throw new BusinessException("没有可更新的课程字段");
        courseMapper.update(null, update);
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public void publishCourse(Long courseId) {
        requireCourse(courseId);
        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("status", "PUBLISHED")
                .setSql("published_at = COALESCE(published_at, CURRENT_TIMESTAMP(3))"));
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public void offlineCourse(Long courseId) {
        requireCourse(courseId);
        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("status", "OFFLINE"));
        indexTaskService.enqueue(courseId);
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

    private String requiredTrim(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new BusinessException(message);
        return trimmed;
    }

    private String nullableTrim(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
