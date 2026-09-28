package com.learnhub.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Set;

@Document(indexName = "learnhub-courses", createIndex = false)
public class CourseSearchDocument {
    @Id
    private Long id;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String instructor;
    @Field(type = FieldType.Keyword, index = false)
    private String coverUrl;
    @Field(type = FieldType.Keyword)
    private Set<String> tags;
    @Field(type = FieldType.Long)
    private long likeCount;

    public CourseSearchDocument() {}

    public CourseSearchDocument(Long id, String title, String description, Set<String> tags, long likeCount) {
        this(id, title, description, null, null, tags, likeCount);
    }

    public CourseSearchDocument(Long id, String title, String description, String instructor, String coverUrl,
                                Set<String> tags, long likeCount) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.instructor = instructor;
        this.coverUrl = coverUrl;
        this.tags = tags == null ? Set.of() : Set.copyOf(tags);
        this.likeCount = likeCount;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getInstructor() { return instructor; }
    public String getCoverUrl() { return coverUrl; }
    public Set<String> getTags() { return tags; }
    public long getLikeCount() { return likeCount; }
}
