package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.CourseTaxonomyRequests;
import com.learnhub.course.mapper.AdminCourseLockMapper;
import com.learnhub.course.mapper.CourseCategoryMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.mapper.CourseTagBindingMapper;
import com.learnhub.course.mapper.CourseTagMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseCategory;
import com.learnhub.course.model.CourseTag;
import com.learnhub.search.CourseIndexTaskService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class CourseTaxonomyServiceTest {
    private final CourseCategoryMapper categoryMapper = mock(CourseCategoryMapper.class);
    private final CourseTagMapper tagMapper = mock(CourseTagMapper.class);
    private final CourseTagBindingMapper bindingMapper = mock(CourseTagBindingMapper.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final AdminCourseLockMapper courseLockMapper = mock(AdminCourseLockMapper.class);
    private final CourseIndexTaskService indexTaskService = mock(CourseIndexTaskService.class);
    private CourseTaxonomyService service;

    @BeforeEach
    void setUp() {
        service = new CourseTaxonomyService(categoryMapper, tagMapper, bindingMapper, courseMapper,
                courseLockMapper, indexTaskService);
    }

    @Test
    void rejectsDeletingReferencedCategory() {
        CourseCategory category = new CourseCategory();
        category.setId(1L);
        when(categoryMapper.selectForUpdate(1L)).thenReturn(category);
        when(courseMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteCategory(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("CATEGORY_IN_USE"));
    }

    @Test
    void replacesCourseTagsAndEnqueuesSearchRefresh() {
        Course course = new Course();
        course.setId(7L);
        CourseTag first = new CourseTag();
        first.setId(11L);
        CourseTag second = new CourseTag();
        second.setId(12L);
        when(courseLockMapper.selectForUpdate(7L)).thenReturn(course);
        when(tagMapper.selectForUpdate(11L)).thenReturn(first);
        when(tagMapper.selectForUpdate(12L)).thenReturn(second);

        service.replaceCourseTags(7L, new AdminCourseRequests.ReplaceTags(Set.of(11L, 12L)));

        verify(bindingMapper).delete(any());
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(any(com.learnhub.course.model.CourseTagBinding.class));
        verify(indexTaskService).enqueue(7L);
    }

    @Test
    void rejectsDisablingCategoryUsedByPublishedCourse() {
        CourseCategory category = new CourseCategory();
        category.setId(1L);
        when(categoryMapper.selectForUpdate(1L)).thenReturn(category);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.updateCategory(1L,
                new CourseTaxonomyRequests.UpdateCategory("后端", 1, "DISABLED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("CATEGORY_IN_USE"));
    }

    @Test
    void convertsConcurrentCategoryAndTagNameConflictsToBusinessErrors() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.insert(any(CourseCategory.class)))
                .thenThrow(new DuplicateKeyException("uk_course_category_name"));
        assertThatThrownBy(() -> service.createCategory(
                new CourseTaxonomyRequests.CreateCategory("  后端  ", 1, "ENABLED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("CATEGORY_NAME_DUPLICATE"));

        when(tagMapper.selectCount(any())).thenReturn(0L);
        when(tagMapper.insert(any(CourseTag.class))).thenThrow(new DuplicateKeyException("uk_tag_name"));
        assertThatThrownBy(() -> service.createTag(new CourseTaxonomyRequests.CreateTag("  Java  ")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("TAG_NAME_DUPLICATE"));
    }
}
