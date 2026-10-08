package com.learnhub.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.service.AdminCourseService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/learnhub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=learnhub", "spring.datasource.password=learnhub",
        "spring.elasticsearch.uris=http://localhost:9200", "learnhub.search.sync-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.like.legacy-key-migration-enabled=false",
        "learnhub.seckill.reconciliation-enabled=false", "learnhub.storage.oss.enabled=false",
        "learnhub.search.analyzer=ik_max_word", "learnhub.search.search-analyzer=ik_smart"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "learnhub.it.enabled", matches = "true")
class CourseIndexSyncInfrastructureIntegrationTest {
    private static final String ALIAS = "learnhub-sync-it-" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("learnhub.search.alias", () -> ALIAS); }
    @Autowired CourseIndexSyncService sync;
    @Autowired CourseIndexTaskService queue;
    @Autowired AdminCourseService admin;
    @Autowired CourseSearchService search;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @SpyBean CourseSearchGateway gateway;
    @SpyBean CourseIndexTaskMapper tasks;
    private final List<Long> courses = new ArrayList<>();
    private final List<String> indexes = new ArrayList<>();
    private final long tagId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
    private final String marker = "syncfixture" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void setup() {
        // 将任务查询限定为本次测试创建的课程，避免消费用户已有同步任务。
        doAnswer(call -> {
            List<CourseIndexTaskEntity> result = new ArrayList<>();
            for (Long id : courses) result.addAll(jdbc.query("SELECT * FROM search_index_task WHERE course_id = ? AND next_retry_at <= NOW(3)", (rs, n) -> {
                var entity = new CourseIndexTaskEntity();
                entity.setId(rs.getLong("id")); entity.setCourseId(id); entity.setGeneration(rs.getLong("generation"));
                entity.setAttempts(rs.getInt("attempts")); return entity;
            }, id));
            return result;
        }).when(tasks).selectDue(anyInt());
    }

    @AfterEach void cleanup() {
        try {
            if (gateway.ensureAlias()) gateway.request("GET", "/_alias/" + ALIAS, null, false).fieldNames().forEachRemaining(indexes::add);
            for (String index : indexes.stream().distinct().toList()) {
                if (gateway.isAliasTarget(index)) gateway.removeAlias(index);
                gateway.deleteIndex(index);
            }
        } finally {
            for (Long id : courses) {
                jdbc.update("DELETE FROM course_tag WHERE course_id = ?", id);
                jdbc.update("DELETE FROM search_index_task WHERE course_id = ?", id);
                jdbc.update("DELETE FROM course WHERE id = ?", id);
            }
            jdbc.update("DELETE FROM tag WHERE id = ?", tagId);
        }
    }

    @Test void courseCreationTagsLikesAndUnpublishingSynchronizeFromMysql() {
        Long id = create();
        assertEquals(1, pending(id));
        jdbc.update("INSERT INTO tag(id,name) VALUES (?,?)", tagId, marker + "|标签");
        jdbc.update("INSERT INTO course_tag(course_id,tag_id) VALUES (?,?)", id, tagId);
        queue.enqueue(id);
        sync.syncDue(100);
        refresh();
        assertEquals(0, pending(id));
        assertEquals(id, search.search(marker, Set.of(marker + "|标签"), 20).get(0).id());
        jdbc.update("UPDATE course SET like_count=200 WHERE id=?", id);
        queue.enqueue(id);
        sync.syncDue(100); refresh();
        assertEquals(200, search.search(marker, Set.of(), 20).get(0).likeCount());
        admin.offlineCourse(id);
        assertEquals(1, pending(id));
        sync.syncDue(100); refresh();
        assertTrue(search.search(marker, Set.of(), 20).isEmpty());
    }

    @Test void failedWritesRetryAndNewGenerationIsNotLostDuringCompletion() {
        Long id = create();
        sync.adminFullRebuild(); refresh();
        queue.enqueue(id);
        doThrow(new SearchUnavailableException(new java.io.IOException("simulated ES outage"))).when(gateway).upsert(any());
        sync.syncDue(100);
        assertEquals(1, pending(id));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM search_index_task WHERE course_id=?", Integer.class, id));
        jdbc.update("UPDATE search_index_task SET next_retry_at=NOW(3) WHERE course_id=?", id);
        doAnswer(call -> {
            call.callRealMethod();
            queue.enqueue(id);
            return null;
        }).when(gateway).upsert(any());
        assertEquals(0, sync.syncDue(100));
        assertEquals(1, pending(id));
        doCallRealMethod().when(gateway).upsert(any());
        sync.syncDue(100); refresh();
        assertEquals(0, pending(id));
        assertEquals(id, search.search(marker, Set.of(), 20).get(0).id());
    }

    @Test void rebuildSwitchesAliasAndFailedRebuildKeepsExistingSearch() throws Exception {
        Long id = create();
        sync.adminFullRebuild(); refresh();
        String oldIndex = currentIndex(); indexes.add(oldIndex);
        sync.adminFullRebuild(); refresh();
        assertNotEquals(oldIndex, currentIndex());
        assertEquals(id, search.search(marker, Set.of(), 20).get(0).id());
        String stableIndex = currentIndex();
        doThrow(new SearchUnavailableException(new java.io.IOException("simulated rebuild failure")))
                .when(gateway).index(anyString(), any());
        assertThrows(SearchUnavailableException.class, sync::adminFullRebuild);
        assertEquals(stableIndex, currentIndex());
        assertEquals(id, search.search(marker, Set.of(), 20).get(0).id());
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(10L, "user"), null, List.of());
        mvc.perform(post("/api/admin/search/courses/rebuild").with(authentication(user))).andExpect(status().isForbidden());
        doThrow(new SearchUnavailableException(new java.io.IOException("offline"))).when(gateway).search(anyMap());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/search/courses?keyword=Java"))
                .andExpect(status().isServiceUnavailable());
    }

    private Long create() {
        Long id = admin.createCourse(new AdminCourseRequests.CreateCourse(1L, marker + " course", "description", "teacher", BigDecimal.ONE, "PUBLISHED"));
        courses.add(id);
        return id;
    }
    private int pending(Long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM search_index_task WHERE course_id=?", Integer.class, id); }
    private String currentIndex() { return gateway.request("GET", "/_alias/" + ALIAS, null, false).fieldNames().next(); }
    private void refresh() { gateway.refresh(gateway.aliasName()); }
}
