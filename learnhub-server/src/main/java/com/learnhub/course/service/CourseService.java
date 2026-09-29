package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.ChapterDetailResponse;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.dto.LessonDetailResponse;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.interaction.like.LikeService;
import com.learnhub.interaction.like.LikeTargetType;
import com.learnhub.order.CourseOrderService;
import com.learnhub.storage.MediaAssetService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CourseService {
    private final CourseMapper courseMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseLessonMapper lessonMapper;
    private final MediaAssetService mediaAssetService;
    private final LikeService likeService;
    private final CourseOrderService orderService;

    public CourseService(CourseMapper courseMapper, CourseChapterMapper chapterMapper,
                         CourseLessonMapper lessonMapper, MediaAssetService mediaAssetService,
                         LikeService likeService, CourseOrderService orderService) {
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.mediaAssetService = mediaAssetService;
        this.likeService = likeService;
        this.orderService = orderService;
    }

    public Page<Course> list(long page, long size, Long categoryId, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<Course> query = new LambdaQueryWrapper<Course>().eq(Course::getStatus, "PUBLISHED")
                .eq(categoryId != null, Course::getCategoryId, categoryId)
                .like(keyword != null && !keyword.isBlank(), Course::getTitle, keyword)
                .orderByDesc(Course::getLikeCount).orderByDesc(Course::getId);
        Page<Course> result = courseMapper.selectPage(Page.of(safePage, safeSize), query);
        result.getRecords().forEach(course -> course.setLikeCount(liveLikeCount(course.getId())));
        return result;
    }

    public CourseDetailResponse detail(Long id) {
        return detail(id, null);
    }

    public CourseDetailResponse detail(Long id, Long userId) {
        return buildDetail(requirePublished(id), userId, false);
    }

    public CourseDetailResponse detailForAdmin(Long id) {
        Course course = courseMapper.selectById(id);
        if (course == null) throw new BusinessException("课程不存在");
        return buildDetail(course, null, true);
    }

    private CourseDetailResponse buildDetail(Course course, Long userId, boolean forceFullAccess) {
        Long id = course.getId();
        boolean authenticated = userId != null || forceFullAccess;
        boolean fullAccess = forceFullAccess || orderService.hasPurchased(userId, id);
        String accessLevel = fullAccess ? "FULL" : authenticated ? "PREVIEW" : "LOGIN_REQUIRED";
        List<CourseChapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<CourseChapter>()
                .eq(CourseChapter::getCourseId, id)
                .orderByAsc(CourseChapter::getSortOrder).orderByAsc(CourseChapter::getId)).stream()
                .sorted(Comparator.comparing(CourseChapter::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CourseChapter::getId))
                .toList();

        List<Long> chapterIds = chapters.stream().map(CourseChapter::getId).toList();
        List<CourseLesson> lessons = chapterIds.isEmpty() ? List.of() : lessonMapper.selectList(
                new LambdaQueryWrapper<CourseLesson>().in(CourseLesson::getChapterId, chapterIds)
                        .orderByAsc(CourseLesson::getSortOrder).orderByAsc(CourseLesson::getId)).stream()
                .sorted(Comparator.comparing(CourseLesson::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CourseLesson::getId))
                .toList();

        Map<Long, List<CourseLesson>> lessonsByChapter = lessons.stream()
                .collect(Collectors.groupingBy(CourseLesson::getChapterId));
        List<ChapterDetailResponse> chapterResponses = chapters.stream()
                .map(chapter -> toChapterResponse(chapter, lessonsByChapter, authenticated, fullAccess))
                .toList();
        return new CourseDetailResponse(course.getId(), course.getTitle(), course.getDescription(),
                course.getCoverUrl(), course.getInstructor(), course.getCategoryId(), course.getPrice(),
                course.getStatus(), liveLikeCount(course.getId()), accessLevel, chapterResponses);
    }

    public Course requirePublished(Long id) {
        Course course = courseMapper.selectOne(new LambdaQueryWrapper<Course>()
                .eq(Course::getId, id).eq(Course::getStatus, "PUBLISHED"));
        if (course == null) throw new BusinessException("课程不存在或未上架");
        return course;
    }

    public void requireLessonAccess(Long userId, Long courseId, Long lessonId) {
        requirePublished(courseId);
        CourseLesson lesson = lessonMapper.selectById(lessonId);
        CourseChapter chapter = lesson == null ? null : chapterMapper.selectById(lesson.getChapterId());
        if (lesson == null || chapter == null || !courseId.equals(chapter.getCourseId())) {
            throw new BusinessException("LESSON_NOT_FOUND", "课时不属于该课程");
        }
        boolean preview = Boolean.TRUE.equals(lesson.getFreePreview());
        if (!preview && !orderService.hasPurchased(userId, courseId)) {
            throw new BusinessException("COURSE_PURCHASE_REQUIRED", "购买课程后才能学习该课时");
        }
    }

    private ChapterDetailResponse toChapterResponse(CourseChapter chapter,
                                                     Map<Long, List<CourseLesson>> lessonsByChapter,
                                                     boolean authenticated,
                                                     boolean fullAccess) {
        List<LessonDetailResponse> lessons = lessonsByChapter.getOrDefault(chapter.getId(), List.of()).stream()
                .map(lesson -> {
                    boolean accessible = authenticated && (fullAccess || Boolean.TRUE.equals(lesson.getFreePreview()));
                    return new LessonDetailResponse(lesson.getId(), lesson.getTitle(),
                            accessible ? resolveMediaUrl(lesson) : null, lesson.getDurationSeconds(),
                            lesson.getFreePreview(), accessible, lesson.getSortOrder());
                })
                .toList();
        return new ChapterDetailResponse(chapter.getId(), chapter.getTitle(), chapter.getSortOrder(), lessons);
    }

    private String resolveMediaUrl(CourseLesson lesson) {
        String signedUrl = mediaAssetService.readyAccessUrl(lesson.getMediaAssetId());
        return signedUrl == null ? lesson.getMediaUrl() : signedUrl;
    }

    private long liveLikeCount(Long courseId) {
        return likeService.count(LikeTargetType.COURSE, courseId);
    }
}
