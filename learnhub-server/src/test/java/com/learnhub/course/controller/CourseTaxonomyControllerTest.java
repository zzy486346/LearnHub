package com.learnhub.course.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import com.learnhub.course.model.CourseCategory;
import com.learnhub.course.model.CourseTag;
import com.learnhub.course.service.CourseTaxonomyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CourseTaxonomyController.class)
@Import(SecurityConfig.class)
class CourseTaxonomyControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private CourseTaxonomyService service;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void anonymousUserCanReadPublicCategoriesAndTags() throws Exception {
        CourseCategory category = new CourseCategory();
        category.setId(9007199254740993L);
        category.setName("后端");
        category.setStatus("ENABLED");
        CourseTag tag = new CourseTag();
        tag.setId(9007199254740995L);
        tag.setName("Java");
        when(service.publicCategories()).thenReturn(List.of(category));
        when(service.publicTags()).thenReturn(List.of(tag));

        mvc.perform(get("/api/course-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("后端"))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"));
        mvc.perform(get("/api/course-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Java"))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740995"));
    }
}
