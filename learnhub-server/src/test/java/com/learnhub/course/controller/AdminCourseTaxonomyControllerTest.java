package com.learnhub.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import com.learnhub.course.model.CourseCategory;
import com.learnhub.course.service.CourseTaxonomyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCourseTaxonomyController.class)
@Import(SecurityConfig.class)
class AdminCourseTaxonomyControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private CourseTaxonomyService service;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void adminCanReadCategoriesAndReplaceCourseTags() throws Exception {
        CourseCategory category = new CourseCategory();
        category.setId(9007199254740993L);
        category.setName("后端");
        category.setSortOrder(10);
        category.setStatus("ENABLED");
        when(service.categories()).thenReturn(List.of(category));

        mvc.perform(get("/api/admin/course-categories").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("后端"))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"));
        when(service.createTag(any())).thenReturn(9007199254740995L);
        mvc.perform(post("/api/admin/course-tags").with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("9007199254740995"));
        mvc.perform(put("/api/admin/courses/1001/tags")
                        .with(authentication(admin())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[1,2]}"))
                .andExpect(status().isOk());

        verify(service).replaceCourseTags(org.mockito.ArgumentMatchers.eq(1001L), any());
    }

    @Test
    void regularUserCannotManageTaxonomy() throws Exception {
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(7L, "learner"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get("/api/admin/course-tags").with(authentication(user)))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(1L, "admin"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
