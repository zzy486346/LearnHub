package com.learnhub.course.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import com.learnhub.course.model.Course;
import com.learnhub.course.service.AdminCourseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCourseController.class)
@Import(SecurityConfig.class)
class AdminCourseControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private AdminCourseService service;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void adminReceivesPagedCoursesAndCanDeleteLesson() throws Exception {
        Course course = new Course();
        course.setId(99L);
        course.setTitle("管理课程");
        Page<Course> page = Page.of(2, 10, 21);
        page.setRecords(List.of(course));
        when(service.list(2)).thenReturn(page);

        mvc.perform(get("/api/admin/courses?page=2").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.records[0].id").value(99));
        mvc.perform(delete("/api/admin/lessons/3001").with(authentication(admin())))
                .andExpect(status().isOk());

        verify(service).list(2);
        verify(service).deleteLesson(3001L);
    }

    @Test
    void nonAdminCannotListCoursesOrDeleteLessons() throws Exception {
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(7L, "learner"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mvc.perform(get("/api/admin/courses").with(authentication(user)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/lessons/3001").with(authentication(user)))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(1L, "admin"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
