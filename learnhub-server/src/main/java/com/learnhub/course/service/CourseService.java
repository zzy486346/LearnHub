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

    public CourseService(CourseMapper courseMapper, CourseChapterMapper chapterMapper,
                         CourseLessonMapper lessonMapper, MediaAssetService mediaAssetService) {
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.mediaAssetService = mediaAssetService;
    }

    public Page<Course> list(long page, long size, Long categoryId, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<Course> query = new LambdaQueryWrapper<Course>().eq(Course::getStatus, "PUBLISHED")
                .eq(categoryId != null, Course::getCategoryId, categoryId)
                .like(keyword != null && !keyword.isBlank(), Course::getTitle, keyword)
                .orderByDesc(Course::getLikeCount).orderByDesc(Course::getId);
        return courseMapper.selectPage(Page.of(safePage, safeSize), query);
    }

    public CourseDetailResponse detail(Long id) {
        Course course = requirePublished(id);
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
                .map(chapter -> toChapterResponse(chapter, lessonsByChapter))
                .toList();
        return new CourseDetailResponse(course.getId(), course.getTitle(), course.getDescription(),
                course.getCoverUrl(), course.getInstructor(), course.getCategoryId(), course.getPrice(),
                course.getStatus(), course.getLikeCount(), chapterResponses);
    }

    public Course requirePublished(Long id) {
        Course course = courseMapper.selectOne(new LambdaQueryWrapper<Course>()
                .eq(Course::getId, id).eq(Course::getStatus, "PUBLISHED"));
        if (course == null) throw new BusinessException("课程不存在或未上架");
        return course;
    }

    private ChapterDetailResponse toChapterResponse(CourseChapter chapter,
                                                     Map<Long, List<CourseLesson>> lessonsByChapter) {
        List<LessonDetailResponse> lessons = lessonsByChapter.getOrDefault(chapter.getId(), List.of()).stream()
                .map(lesson -> new LessonDetailResponse(lesson.getId(), lesson.getTitle(), resolveMediaUrl(lesson),
                        lesson.getDurationSeconds(), lesson.getFreePreview(), lesson.getSortOrder()))
                .toList();
        return new ChapterDetailResponse(chapter.getId(), chapter.getTitle(), chapter.getSortOrder(), lessons);
    }

    private String resolveMediaUrl(CourseLesson lesson) {
        String signedUrl = mediaAssetService.readyAccessUrl(lesson.getMediaAssetId());
        return signedUrl == null ? lesson.getMediaUrl() : signedUrl;
    }
}
