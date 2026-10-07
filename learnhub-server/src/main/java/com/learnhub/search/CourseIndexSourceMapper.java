package com.learnhub.search;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseIndexSourceMapper {
    @Select("""
            SELECT c.id, c.title, c.description, c.instructor, c.cover_url, c.like_count,
                   c.status, c.published_at, c.deleted,
                   (SELECT JSON_ARRAYAGG(t.name)
                    FROM course_tag ct JOIN tag t ON t.id = ct.tag_id
                    WHERE ct.course_id = c.id) AS tag_json
            FROM course c
            WHERE c.id = #{courseId}
            """)
    CourseIndexSource selectByCourseId(@Param("courseId") Long courseId);

    @Select("""
            SELECT c.id, c.title, c.description, c.instructor, c.cover_url, c.like_count,
                   c.status, c.published_at, c.deleted,
                   (SELECT JSON_ARRAYAGG(t.name)
                    FROM course_tag ct JOIN tag t ON t.id = ct.tag_id
                    WHERE ct.course_id = c.id) AS tag_json
            FROM course c
            WHERE c.id > #{afterId}
            ORDER BY c.id
            LIMIT #{limit}
            """)
    List<CourseIndexSource> selectPageAfterId(@Param("afterId") long afterId, @Param("limit") int limit);

    class CourseIndexSource {
        private Long id;
        private String title;
        private String description;
        private String instructor;
        private String coverUrl;
        private Long likeCount;
        private String status;
        private LocalDateTime publishedAt;
        private Boolean deleted;
        private String tagJson;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getInstructor() { return instructor; }
        public void setInstructor(String instructor) { this.instructor = instructor; }
        public String getCoverUrl() { return coverUrl; }
        public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
        public Long getLikeCount() { return likeCount; }
        public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getPublishedAt() { return publishedAt; }
        public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
        public Boolean getDeleted() { return deleted; }
        public void setDeleted(Boolean deleted) { this.deleted = deleted; }
        public String getTagJson() { return tagJson; }
        public void setTagJson(String tagJson) { this.tagJson = tagJson; }
    }
}
