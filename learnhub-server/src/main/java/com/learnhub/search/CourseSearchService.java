package com.learnhub.search;

import com.learnhub.course.model.Course;
import com.learnhub.course.service.CourseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourseSearchService {
    private final CourseSearchRepository repository;
    private final CourseService courseService;
    private final ConcurrentHashMap<Long, CourseSearchDocument> fallbackIndex = new ConcurrentHashMap<>();

    @Autowired
    public CourseSearchService(ObjectProvider<CourseSearchRepository> repositoryProvider,
                               CourseService courseService) {
        this.repository = repositoryProvider.getIfAvailable();
        this.courseService = courseService;
    }

    CourseSearchService(ObjectProvider<CourseSearchRepository> repositoryProvider) {
        this(repositoryProvider, null);
    }

    public CourseSearchDocument index(CourseSearchDocument document) {
        fallbackIndex.put(document.getId(), document);
        if (repository != null) {
            try {
                repository.save(document);
            } catch (RuntimeException ignored) {
                // Development can continue without Elasticsearch; data stays searchable in this process.
            }
        }
        return document;
    }

    public List<SearchResult> search(String keyword, Set<String> tags, int limit) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return documents().stream()
                .filter(document -> matches(document, normalized))
                .filter(document -> tags == null || tags.isEmpty() || tagsOf(document).containsAll(tags))
                .map(document -> toResult(document, normalized))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    public List<String> suggest(String prefix, int limit) {
        String normalized = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        return documents().stream()
                .map(CourseSearchDocument::getTitle)
                .filter(title -> title != null && title.toLowerCase(Locale.ROOT).startsWith(normalized))
                .distinct()
                .sorted()
                .limit(Math.max(1, Math.min(limit, 20)))
                .toList();
    }

    private Collection<CourseSearchDocument> documents() {
        Map<Long, CourseSearchDocument> documents = new LinkedHashMap<>(fallbackIndex);
        if (repository != null) {
            try {
                repository.findAll().forEach(document -> documents.put(document.getId(), document));
            } catch (RuntimeException ignored) {
                // Explicit fallback boundary for local runs where Elasticsearch is disabled/unavailable.
            }
        }
        if (courseService != null) {
            List<Course> databaseCourses = courseService.list(1, 100, null, null).getRecords();
            databaseCourses.forEach(course -> {
                CourseSearchDocument indexed = documents.get(course.getId());
                Set<String> tags = indexed == null || indexed.getTags() == null ? Set.of() : indexed.getTags();
                documents.put(course.getId(), new CourseSearchDocument(
                        course.getId(), course.getTitle(), course.getDescription(), course.getInstructor(),
                        course.getCoverUrl(), tags, course.getLikeCount() == null ? 0L : course.getLikeCount()));
            });
        }
        fallbackIndex.putAll(documents);
        return documents.values();
    }

    private boolean matches(CourseSearchDocument document, String keyword) {
        if (keyword.isBlank()) return true;
        return contains(document.getTitle(), keyword) || contains(document.getDescription(), keyword)
                || contains(document.getInstructor(), keyword)
                || tagsOf(document).stream().anyMatch(tag -> contains(tag, keyword));
    }

    private SearchResult toResult(CourseSearchDocument document, String keyword) {
        double relevance = keyword.isBlank() ? 1 : 0;
        if (contains(document.getTitle(), keyword)) relevance += 3;
        if (contains(document.getDescription(), keyword)) relevance += 1;
        if (contains(document.getInstructor(), keyword)) relevance += 2;
        if (tagsOf(document).stream().anyMatch(tag -> contains(tag, keyword))) relevance += 2;
        double businessWeight = Math.log1p(Math.max(0, document.getLikeCount())) * 0.2;
        return new SearchResult(document.getId(), document.getTitle(), document.getDescription(),
                document.getInstructor(), document.getCoverUrl(), tagsOf(document), document.getLikeCount(),
                relevance + businessWeight);
    }

    private Set<String> tagsOf(CourseSearchDocument document) {
        return document.getTags() == null ? Set.of() : document.getTags();
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
