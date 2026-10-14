package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.AdminCourseLockMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.search.CourseIndexTaskService;
import com.learnhub.storage.MediaAssetService;
import com.learnhub.storage.MediaAssetMapper;
import com.learnhub.storage.MediaAsset;
import com.learnhub.storage.MediaResponses;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminCourseService {
    private final CourseMapper courseMapper;
    private final AdminCourseLockMapper courseLockMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseLessonMapper lessonMapper;
    private final CourseService courseService;
    private final CourseTaxonomyService taxonomyService;
    private final MediaAssetService mediaAssetService;
    private final MediaAssetMapper mediaAssetMapper;
    private final CourseIndexTaskService indexTaskService;

    public AdminCourseService(CourseMapper courseMapper, AdminCourseLockMapper courseLockMapper,
                              CourseChapterMapper chapterMapper, CourseLessonMapper lessonMapper,
                              CourseService courseService, CourseTaxonomyService taxonomyService,
                              MediaAssetService mediaAssetService, MediaAssetMapper mediaAssetMapper,
                              CourseIndexTaskService indexTaskService) {
        this.courseMapper = courseMapper;
        this.courseLockMapper = courseLockMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.courseService = courseService;
        this.taxonomyService = taxonomyService;
        this.mediaAssetService = mediaAssetService;
        this.mediaAssetMapper = mediaAssetMapper;
        this.indexTaskService = indexTaskService;
    }

    public Page<Course> list(long page) {
        long safePage = Math.max(1, page);
        return courseMapper.selectPage(Page.of(safePage, 10),
                new LambdaQueryWrapper<Course>().orderByDesc(Course::getId));
    }

    public CourseDetailResponse detail(Long courseId) {
        return courseService.detailForAdmin(courseId);
    }

    @Transactional
    public Long createCourse(AdminCourseRequests.CreateCourse request) {
        if (request.status() != null && !"DRAFT".equals(request.status())) {
            throw new BusinessException("COURSE_DRAFT_REQUIRED", "新建课程必须先保存为草稿");
        }
        taxonomyService.requireEnabledCategory(request.categoryId());
        Course course = new Course();
        course.setCategoryId(request.categoryId());
        course.setTitle(request.title().trim());
        course.setDescription(request.description() == null ? null : request.description().trim());
        course.setInstructor(request.instructor().trim());
        course.setPrice(request.price());
        course.setStatus("DRAFT");
        course.setLikeCount(0L);
        courseMapper.insert(course);
        indexTaskService.enqueue(course.getId());
        return course.getId();
    }

    @Transactional
    public void updateCourse(Long courseId, AdminCourseRequests.UpdateCourse request) {
        Course course = requireCourseForUpdate(courseId);
        UpdateWrapper<Course> update = new UpdateWrapper<Course>().eq("id", courseId);
        boolean changed = false;
        if (request.categoryId() != null) {
            taxonomyService.requireEnabledCategory(request.categoryId());
            update.set("category_id", request.categoryId());
            changed = true;
        }
        if (request.title() != null) { update.set("title", requiredTrim(request.title(), "课程标题不能为空")); changed = true; }
        if (request.subtitle() != null) { update.set("subtitle", nullableTrim(request.subtitle())); changed = true; }
        if (request.coverUrl() != null) {
            update.set("cover_url", nullableTrim(request.coverUrl()));
            update.set("cover_media_id", null);
            changed = true;
        }
        if (request.description() != null) { update.set("description", nullableTrim(request.description())); changed = true; }
        if (request.instructor() != null) { update.set("instructor", requiredTrim(request.instructor(), "讲师不能为空")); changed = true; }
        if (request.price() != null) { update.set("price", request.price()); changed = true; }
        if (!changed) throw new BusinessException("没有可更新的课程字段");
        courseMapper.update(null, update);
        if ("PUBLISHED".equals(course.getStatus())) requirePublishable(courseId);
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public void publishCourse(Long courseId) {
        requireCourseForUpdate(courseId);
        requirePublishable(courseId);
        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("status", "PUBLISHED")
                .setSql("published_at = COALESCE(published_at, CURRENT_TIMESTAMP(3))"));
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public void offlineCourse(Long courseId) {
        requireCourseForUpdate(courseId);
        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("status", "OFFLINE"));
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public Long createChapter(Long courseId, AdminCourseRequests.CreateChapter request) {
        Course course = requireCourseForUpdate(courseId);
        if ("PUBLISHED".equals(course.getStatus())) {
            throw new BusinessException("COURSE_OFFLINE_REQUIRED", "已发布课程新增章节前请先下架");
        }
        CourseChapter chapter = new CourseChapter();
        chapter.setCourseId(courseId);
        chapter.setTitle(requiredTrim(request.title(), "章节标题不能为空"));
        chapter.setSortOrder(request.sortOrder());
        chapterMapper.insert(chapter);
        return chapter.getId();
    }

    @Transactional
    public void updateChapter(Long chapterId, AdminCourseRequests.UpdateChapter request) {
        CourseChapter chapter = requireChapter(chapterId);
        requireCourseForUpdate(chapter.getCourseId());
        chapterMapper.update(null, new UpdateWrapper<CourseChapter>()
                .eq("id", chapterId)
                .set("title", requiredTrim(request.title(), "章节标题不能为空"))
                .set("sort_order", request.sortOrder()));
    }

    @Transactional
    public Long createLesson(Long chapterId, AdminCourseRequests.CreateLesson request) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) throw new BusinessException("章节不存在");
        Course course = requireCourseForUpdate(chapter.getCourseId());
        if ("PUBLISHED".equals(course.getStatus())) {
            throw new BusinessException("COURSE_OFFLINE_REQUIRED", "已发布课程新增课时前请先下架");
        }
        CourseLesson lesson = new CourseLesson();
        lesson.setChapterId(chapterId);
        lesson.setTitle(requiredTrim(request.title(), "课时标题不能为空"));
        lesson.setDurationSeconds(request.durationSeconds());
        lesson.setFreePreview(request.freePreview());
        lesson.setSortOrder(request.sortOrder());
        lessonMapper.insert(lesson);
        return lesson.getId();
    }

    @Transactional
    public void updateLesson(Long lessonId, AdminCourseRequests.UpdateLesson request) {
        CourseLesson lesson = requireLesson(lessonId);
        CourseChapter chapter = requireChapter(lesson.getChapterId());
        Course course = requireCourseForUpdate(chapter.getCourseId());
        lessonMapper.update(null, new UpdateWrapper<CourseLesson>()
                .eq("id", lessonId)
                .set("title", requiredTrim(request.title(), "课时标题不能为空"))
                .set("duration_seconds", request.durationSeconds())
                .set("free_preview", request.freePreview())
                .set("sort_order", request.sortOrder()));
        if ("PUBLISHED".equals(course.getStatus())) requirePublishable(course.getId());
    }

    @Transactional
    public void deleteLesson(Long lessonId) {
        CourseLesson lesson = requireLesson(lessonId);
        CourseChapter chapter = requireChapter(lesson.getChapterId());
        Course course = requireCourseForUpdate(chapter.getCourseId());
        lessonMapper.deleteById(lessonId);
        if ("PUBLISHED".equals(course.getStatus())) {
            try {
                requirePublishable(course.getId());
            } catch (BusinessException exception) {
                throw new BusinessException("COURSE_OFFLINE_REQUIRED", "删除会破坏已发布课程完整性，请先下架课程");
            }
        }
    }

    @Transactional
    public MediaResponses.Asset uploadLessonVideo(Long adminUserId, Long lessonId, MultipartFile file) {
        CourseLesson lesson = requireLesson(lessonId);
        CourseChapter chapter = requireChapter(lesson.getChapterId());
        Course course = requireCourseForUpdate(chapter.getCourseId());
        MediaResponses.Asset asset = mediaAssetService.uploadCourseVideo(adminUserId, lessonId, file);
        lessonMapper.update(null, new UpdateWrapper<CourseLesson>()
                .eq("id", lessonId)
                .set("media_asset_id", asset.id())
                .set("media_url", null));
        if ("PUBLISHED".equals(course.getStatus())) requirePublishable(course.getId());
        return asset;
    }

    @Transactional
    public MediaResponses.Asset uploadCourseCover(Long adminUserId, Long courseId, MultipartFile file) {
        requireCourseForUpdate(courseId);
        MediaResponses.Asset asset = mediaAssetService.uploadCourseCover(adminUserId, courseId, file);
        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("cover_media_id", asset.id())
                .set("cover_url", "/api/courses/" + courseId + "/cover"));
        indexTaskService.enqueue(courseId);
        return asset;
    }

    private Course requireCourse(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) throw new BusinessException("课程不存在");
        return course;
    }

    private Course requireCourseForUpdate(Long courseId) {
        Course course = courseLockMapper.selectForUpdate(courseId);
        if (course == null) throw new BusinessException("课程不存在");
        return course;
    }

    private CourseChapter requireChapter(Long chapterId) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) throw new BusinessException("章节不存在");
        return chapter;
    }

    private CourseLesson requireLesson(Long lessonId) {
        CourseLesson lesson = lessonMapper.selectById(lessonId);
        if (lesson == null) throw new BusinessException("课时不存在");
        return lesson;
    }

    private void requirePublishable(Long courseId) {
        Course course = requireCourse(courseId);
        taxonomyService.requireEnabledCategory(course.getCategoryId());
        List<CourseChapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<CourseChapter>()
                .eq(CourseChapter::getCourseId, courseId));
        if (chapters.isEmpty()) {
            throw new BusinessException("COURSE_NOT_READY", "课程至少需要一个章节才能发布");
        }
        List<Long> chapterIds = chapters.stream().map(CourseChapter::getId).toList();
        List<CourseLesson> lessons = lessonMapper.selectList(new LambdaQueryWrapper<CourseLesson>()
                .in(CourseLesson::getChapterId, chapterIds));
        if (lessons.isEmpty()) {
            throw new BusinessException("COURSE_NOT_READY", "课程至少需要一个课时才能发布");
        }
        Set<Long> chapterIdsWithLessons = new HashSet<>();
        for (CourseLesson lesson : lessons) chapterIdsWithLessons.add(lesson.getChapterId());
        for (CourseChapter chapter : chapters) {
            if (!chapterIdsWithLessons.contains(chapter.getId())) {
                throw new BusinessException("COURSE_NOT_READY",
                        "章节“" + chapter.getTitle() + "”至少需要一个课时才能发布");
            }
        }
        for (CourseLesson lesson : lessons) {
            if (lesson.getDurationSeconds() == null || lesson.getDurationSeconds() <= 0) {
                throw new BusinessException("COURSE_NOT_READY", "所有课时必须设置有效时长");
            }
            if (!hasReadyMedia(lesson)) {
                throw new BusinessException("COURSE_NOT_READY", "所有课时必须上传可用视频");
            }
        }
    }

    private boolean hasReadyMedia(CourseLesson lesson) {
        if (lesson.getMediaAssetId() != null) {
            MediaAsset asset = mediaAssetMapper.selectById(lesson.getMediaAssetId());
            if (asset != null && "READY".equals(asset.getStatus())
                    && "COURSE_LESSON".equals(asset.getBizType())
                    && lesson.getId().equals(asset.getBizId())) return true;
        }
        String mediaUrl = lesson.getMediaUrl();
        if (mediaUrl == null || mediaUrl.isBlank()) return false;
        try {
            URI uri = new URI(mediaUrl.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (URISyntaxException exception) {
            return false;
        }
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
