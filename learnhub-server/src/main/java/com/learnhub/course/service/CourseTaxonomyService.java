package com.learnhub.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.learnhub.course.model.CourseTagBinding;
import com.learnhub.search.CourseIndexTaskService;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseTaxonomyService {
    private final CourseCategoryMapper categoryMapper;
    private final CourseTagMapper tagMapper;
    private final CourseTagBindingMapper bindingMapper;
    private final CourseMapper courseMapper;
    private final AdminCourseLockMapper courseLockMapper;
    private final CourseIndexTaskService indexTaskService;

    public CourseTaxonomyService(CourseCategoryMapper categoryMapper, CourseTagMapper tagMapper,
                                 CourseTagBindingMapper bindingMapper, CourseMapper courseMapper,
                                 AdminCourseLockMapper courseLockMapper,
                                 CourseIndexTaskService indexTaskService) {
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.bindingMapper = bindingMapper;
        this.courseMapper = courseMapper;
        this.courseLockMapper = courseLockMapper;
        this.indexTaskService = indexTaskService;
    }

    public List<CourseCategory> categories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CourseCategory>()
                .orderByAsc(CourseCategory::getSortOrder).orderByAsc(CourseCategory::getId));
    }

    public List<CourseCategory> publicCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CourseCategory>()
                .eq(CourseCategory::getStatus, "ENABLED")
                .orderByAsc(CourseCategory::getSortOrder).orderByAsc(CourseCategory::getId));
    }

    @Transactional
    public Long createCategory(CourseTaxonomyRequests.CreateCategory request) {
        String name = normalizedName(request.name());
        requireCategoryNameAvailable(name, null);
        LocalDateTime now = LocalDateTime.now();
        CourseCategory category = new CourseCategory();
        category.setName(name);
        category.setSortOrder(request.sortOrder());
        category.setStatus(request.status());
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException duplicate) {
            throw duplicateCategoryName();
        }
        return category.getId();
    }

    @Transactional
    public void updateCategory(Long categoryId, CourseTaxonomyRequests.UpdateCategory request) {
        requireCategoryForUpdate(categoryId);
        String name = normalizedName(request.name());
        requireCategoryNameAvailable(name, categoryId);
        if ("DISABLED".equals(request.status()) && courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getCategoryId, categoryId).eq(Course::getStatus, "PUBLISHED")) > 0) {
            throw new BusinessException("CATEGORY_IN_USE", "存在已发布课程，不能停用该分类");
        }
        try {
            categoryMapper.update(null, new LambdaUpdateWrapper<CourseCategory>()
                    .eq(CourseCategory::getId, categoryId)
                    .set(CourseCategory::getName, name)
                    .set(CourseCategory::getSortOrder, request.sortOrder())
                    .set(CourseCategory::getStatus, request.status())
                    .set(CourseCategory::getUpdatedAt, LocalDateTime.now()));
        } catch (DuplicateKeyException duplicate) {
            throw duplicateCategoryName();
        }
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        requireCategoryForUpdate(categoryId);
        if (courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getCategoryId, categoryId)) > 0) {
            throw new BusinessException("CATEGORY_IN_USE", "分类已被课程引用，不能删除");
        }
        categoryMapper.deleteById(categoryId);
    }

    public List<CourseTag> tags() {
        return tagMapper.selectList(new LambdaQueryWrapper<CourseTag>()
                .orderByAsc(CourseTag::getName).orderByAsc(CourseTag::getId));
    }

    public List<CourseTag> publicTags() {
        return tagMapper.selectPublishedTags();
    }

    @Transactional
    public Long createTag(CourseTaxonomyRequests.CreateTag request) {
        String name = normalizedName(request.name());
        requireTagNameAvailable(name, null);
        LocalDateTime now = LocalDateTime.now();
        CourseTag tag = new CourseTag();
        tag.setName(name);
        tag.setCreatedAt(now);
        tag.setUpdatedAt(now);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException duplicate) {
            throw duplicateTagName();
        }
        return tag.getId();
    }

    @Transactional
    public void updateTag(Long tagId, CourseTaxonomyRequests.UpdateTag request) {
        requireTagForUpdate(tagId);
        String name = normalizedName(request.name());
        requireTagNameAvailable(name, tagId);
        List<Long> affectedCourses = courseIdsForTag(tagId);
        try {
            tagMapper.update(null, new LambdaUpdateWrapper<CourseTag>()
                    .eq(CourseTag::getId, tagId)
                    .set(CourseTag::getName, name)
                    .set(CourseTag::getUpdatedAt, LocalDateTime.now()));
        } catch (DuplicateKeyException duplicate) {
            throw duplicateTagName();
        }
        indexTaskService.enqueueAll(affectedCourses);
    }

    @Transactional
    public void deleteTag(Long tagId) {
        requireTagForUpdate(tagId);
        if (bindingMapper.selectCount(new LambdaQueryWrapper<CourseTagBinding>()
                .eq(CourseTagBinding::getTagId, tagId)) > 0) {
            throw new BusinessException("TAG_IN_USE", "标签已被课程引用，不能删除");
        }
        tagMapper.deleteById(tagId);
    }

    public List<CourseTag> courseTags(Long courseId) {
        requireCourse(courseId);
        List<Long> tagIds = bindingMapper.selectList(new LambdaQueryWrapper<CourseTagBinding>()
                        .eq(CourseTagBinding::getCourseId, courseId))
                .stream().map(CourseTagBinding::getTagId).toList();
        if (tagIds.isEmpty()) return List.of();
        return tagMapper.selectList(new LambdaQueryWrapper<CourseTag>()
                .in(CourseTag::getId, tagIds).orderByAsc(CourseTag::getName).orderByAsc(CourseTag::getId));
    }

    @Transactional
    public void replaceCourseTags(Long courseId, AdminCourseRequests.ReplaceTags request) {
        if (courseLockMapper.selectForUpdate(courseId) == null) throw new BusinessException("课程不存在");
        Set<Long> tagIds = new LinkedHashSet<>(request.tagIds());
        if (!tagIds.isEmpty()) {
            for (Long tagId : tagIds.stream().sorted().toList()) {
                requireTagForUpdate(tagId);
            }
        }
        bindingMapper.delete(new LambdaQueryWrapper<CourseTagBinding>()
                .eq(CourseTagBinding::getCourseId, courseId));
        for (Long tagId : tagIds) {
            CourseTagBinding binding = new CourseTagBinding();
            binding.setCourseId(courseId);
            binding.setTagId(tagId);
            bindingMapper.insert(binding);
        }
        indexTaskService.enqueue(courseId);
    }

    @Transactional
    public void requireEnabledCategory(Long categoryId) {
        CourseCategory category = categoryMapper.selectForUpdate(categoryId);
        if (category == null || !"ENABLED".equals(category.getStatus())) {
            throw new BusinessException("CATEGORY_NOT_AVAILABLE", "课程分类不存在或已停用");
        }
    }

    private void requireCourse(Long courseId) {
        if (courseMapper.selectById(courseId) == null) throw new BusinessException("课程不存在");
    }

    private CourseCategory requireCategoryForUpdate(Long categoryId) {
        CourseCategory category = categoryMapper.selectForUpdate(categoryId);
        if (category == null) throw new BusinessException("CATEGORY_NOT_FOUND", "课程分类不存在");
        return category;
    }

    private CourseTag requireTagForUpdate(Long tagId) {
        CourseTag tag = tagMapper.selectForUpdate(tagId);
        if (tag == null) throw new BusinessException("TAG_NOT_FOUND", "课程标签不存在");
        return tag;
    }

    private void requireCategoryNameAvailable(String name, Long excludedId) {
        long count = categoryMapper.selectCount(new LambdaQueryWrapper<CourseCategory>()
                .eq(CourseCategory::getName, name).ne(excludedId != null, CourseCategory::getId, excludedId));
        if (count > 0) throw duplicateCategoryName();
    }

    private void requireTagNameAvailable(String name, Long excludedId) {
        long count = tagMapper.selectCount(new LambdaQueryWrapper<CourseTag>()
                .eq(CourseTag::getName, name).ne(excludedId != null, CourseTag::getId, excludedId));
        if (count > 0) throw duplicateTagName();
    }

    private BusinessException duplicateCategoryName() {
        return new BusinessException("CATEGORY_NAME_DUPLICATE", "课程分类名称已存在");
    }

    private BusinessException duplicateTagName() {
        return new BusinessException("TAG_NAME_DUPLICATE", "课程标签名称已存在");
    }

    private List<Long> courseIdsForTag(Long tagId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<CourseTagBinding>()
                        .eq(CourseTagBinding::getTagId, tagId))
                .stream().map(CourseTagBinding::getCourseId).distinct().toList();
    }

    private String normalizedName(String value) {
        return value.trim();
    }
}
