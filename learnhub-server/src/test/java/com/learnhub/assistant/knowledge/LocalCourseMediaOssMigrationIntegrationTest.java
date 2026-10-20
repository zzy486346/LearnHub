package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.learnhub.course.service.AdminCourseService;
import com.learnhub.interaction.like.LikeLegacyKeyMigration;
import com.learnhub.interaction.like.LikeSequenceService;
import com.learnhub.search.CourseIndexSyncService;
import com.learnhub.storage.ObjectStorageService;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;

/** 显式授权的本机维护：恢复媒体上传 OSS，再绑定现有课程；不创建 HTTP 服务。 */
@EnabledIfSystemProperty(named = "learnhub.media.local.migrate", matches = "true")
@ContextConfiguration(initializers = CatalogMaterialImportIntegrationTest.LocalOnly.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.knowledge.sync-enabled=false", "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false", "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false", "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false", "learnhub.assistant.chat-enabled=false",
        "learnhub.assistant.langchain-enabled=false", "learnhub.assistant.memory.enabled=false",
        "learnhub.vector.enabled=false", "learnhub.ai.mode=DISABLED"
})
class LocalCourseMediaOssMigrationIntegrationTest {
    @MockBean LikeLegacyKeyMigration legacyLikeMigration;
    @MockBean LikeSequenceService likeSequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminCourseService admin;
    @Autowired ObjectStorageService storage;
    @Autowired CourseIndexSyncService searchSync;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Test void uploadsRecoveredMediaAndVerifiesEveryBoundObject() throws Exception {
        Path root = CatalogMaterialCorpusTest.ROOT;
        Path media = root.resolve(".local/course-media-recovery").toRealPath();
        JsonNode catalog = CatalogMaterialCorpusTest.JSON.readTree(Files.readString(root.resolve("scripts/course-media/catalog.json")));
        JsonNode manifest = CatalogMaterialCorpusTest.JSON.readTree(Files.readString(media.resolve("manifest.json")));
        assertThat(catalog.size()).isEqualTo(21);
        assertThat(manifest.path("courses").size()).isEqualTo(21);
        List<Item> plan = new ArrayList<>();
        for (int n = 0; n < catalog.size(); n++) {
            JsonNode course = catalog.get(n);
            String slug = course.path("slug").asText();
            assertThat(slug).matches("[a-z0-9-]+");
            long courseId = 12001L + n;
            var row = jdbc.queryForMap("SELECT title,cover_url,cover_media_id FROM course WHERE id=? AND deleted=0", courseId);
            assertThat(row.get("title")).isEqualTo(course.path("title").asText());
            JsonNode generated = null;
            for (JsonNode candidate : manifest.path("courses")) if (slug.equals(candidate.path("slug").asText())) generated = candidate;
            assertThat(generated).isNotNull();
            plan.add(item(media, courseId, courseId, true, slug, "cover.png", row.get("cover_url"), row.get("cover_media_id"), 0));
            for (int lesson = 0; lesson < 2; lesson++) {
                var lessons = jdbc.queryForList("""
                        SELECT l.id,l.media_url,l.media_asset_id FROM course_lesson l
                        JOIN course_chapter ch ON ch.id=l.chapter_id WHERE ch.course_id=? AND l.deleted=0 AND l.title=?
                        """, courseId, course.path("lessons").get(lesson).path("title").asText());
                assertThat(lessons).hasSize(1);
                var found = lessons.get(0);
                String filename = "lesson-%02d.mp4".formatted(lesson + 1);
                int duration = generated.path("lessons").get(lesson).path("durationSeconds").asInt();
                assertThat(duration).isPositive();
                plan.add(item(media, courseId, ((Number) found.get("id")).longValue(), false, slug, filename,
                        found.get("media_url"), found.get("media_asset_id"), duration));
            }
        }
        assertThat(plan).hasSize(63);
        Long owner = jdbc.queryForObject("""
                SELECT u.id FROM user u JOIN user_role r ON r.user_id=u.id
                WHERE u.deleted=0 AND u.status='ACTIVE' AND r.role_code='ADMIN' ORDER BY u.id LIMIT 1
                """, Long.class);
        var protectedRows = protectedMedia();
        Path backup = root.resolve(".local/course-media-migration-before.json");
        try { Files.writeString(backup, CatalogMaterialCorpusTest.JSON.copy().findAndRegisterModules()
                .writeValueAsString(jdbc.queryForList("""
                        SELECT c.id,c.cover_url,c.cover_media_id,l.id AS lesson_id,l.media_url,l.media_asset_id,l.duration_seconds
                        FROM course c JOIN course_chapter ch ON ch.course_id=c.id JOIN course_lesson l ON l.chapter_id=ch.id
                        WHERE c.id BETWEEN 12001 AND 12021 ORDER BY c.id,l.id
                        """)), StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException preserved) { /* 保留首次回退信息。 */ }
        int uploaded = 0;
        long bytes = 0;
        for (Item item : plan) {
            Long assetId = item.existingAsset();
            if (assetId == null) {
                MockMultipartFile file;
                try (var input = Files.newInputStream(item.path())) {
                    file = new MockMultipartFile("file", item.path().getFileName().toString(), item.type(), input);
                }
                assetId = item.cover() ? admin.uploadCourseCover(owner, item.businessId(), file).id()
                        : admin.uploadLessonVideo(owner, item.businessId(), file).id();
                uploaded++;
            }
            verify(item, assetId);
            if (!item.cover()) jdbc.update("UPDATE course_lesson SET duration_seconds=? WHERE id=? AND media_asset_id=?",
                    item.duration(), item.businessId(), assetId);
            bytes += Files.size(item.path());
        }
        assertThat(protectedMedia()).as("原三门课程及九个已绑定视频不能被修改").isEqualTo(protectedRows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE deleted=0 AND cover_url LIKE '%8091%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_lesson WHERE deleted=0 AND media_url LIKE '%8091%'", Integer.class)).isZero();
        searchSync.syncDue(100);
        Files.writeString(root.resolve(".local/course-media-oss-migration-latest.json"),
                CatalogMaterialCorpusTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "courses", 21, "coversVerified", 21, "videosVerified", 42, "uploadedThisRun", uploaded,
                        "totalBytes", bytes, "headChecks", 63, "httpChecks", 63, "protectedMediaUnchanged", true,
                        "remainingLocalMediaReferences", 0)));
    }

    private Item item(Path root, long course, long business, boolean cover, String slug, String filename,
                      Object oldUrl, Object asset, int duration) throws Exception {
        Path file = root.resolve("courses").resolve(slug).resolve(filename).toRealPath();
        assertThat(file.startsWith(root)).isTrue();
        assertThat(Files.size(file)).isPositive();
        Long existing = asset == null ? null : ((Number) asset).longValue();
        if (existing == null) assertThat(oldUrl).isEqualTo("http://localhost:8091/courses/" + slug + "/" + (cover ? "cover.svg" : filename));
        return new Item(course, business, cover, file, existing, duration);
    }

    private void verify(Item item, long assetId) throws Exception {
        var asset = jdbc.queryForMap("SELECT status,biz_type,biz_id,object_key,file_size FROM media_asset WHERE id=? AND deleted=0", assetId);
        assertThat(asset.get("status")).isEqualTo("READY");
        assertThat(asset.get("biz_type")).isEqualTo(item.cover() ? "COURSE_COVER" : "COURSE_LESSON");
        assertThat(((Number) asset.get("biz_id")).longValue()).isEqualTo(item.businessId());
        String key = (String) asset.get("object_key");
        var metadata = storage.metadata(key);
        assertThat(metadata.size()).isEqualTo(Files.size(item.path()));
        assertThat(((Number) asset.get("file_size")).longValue()).isEqualTo(metadata.size());
        assertThat(metadata.contentType()).startsWith(item.type());
        var access = storage.accessUrl(key, Duration.ofMinutes(5), item.cover());
        var request = HttpRequest.newBuilder(access.url()).timeout(Duration.ofSeconds(30))
                .header("Range", "bytes=0-31").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertThat(response.statusCode()).as("媒体HTTP访问状态，禁止记录签名URL").isIn(item.cover() ? new Integer[]{200, 206} : new Integer[]{206});
            assertThat(body.readNBytes(32)).hasSize(32);
        }
    }

    private List<Map<String, Object>> protectedMedia() {
        return jdbc.queryForList("""
                SELECT c.id,c.cover_url,c.cover_media_id,l.id AS lesson_id,l.media_url,l.media_asset_id,l.duration_seconds
                FROM course c JOIN course_chapter ch ON ch.course_id=c.id JOIN course_lesson l ON l.chapter_id=ch.id
                WHERE c.id NOT BETWEEN 12001 AND 12021 ORDER BY c.id,l.id
                """);
    }

    private record Item(long course, long businessId, boolean cover, Path path, Long existingAsset, int duration) {
        String type() { return cover ? "image/png" : "video/mp4"; }
    }
}
