package com.learnhub.search;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourseSearchService {
    private final CourseSearchRepository repository;
    private final ConcurrentHashMap<Long, CourseSearchDocument> fallbackIndex = new ConcurrentHashMap<>();

    public CourseSearchService(ObjectProvider<CourseSearchRepository> repositoryProvider) {
        this.repository = repositoryProvider.getIfAvailable();
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
                .filter(document -> tags == null || tags.isEmpty() || document.getTags().containsAll(tags))
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
        if (repository != null) {
            try {
                List<CourseSearchDocument> documents = new ArrayList<>();
                repository.findAll().forEach(documents::add);
                documents.forEach(document -> fallbackIndex.put(document.getId(), document));
                return documents;
            } catch (RuntimeException ignored) {
                // Explicit fallback boundary for local runs where Elasticsearch is disabled/unavailable.
            }
        }
        return fallbackIndex.values();
    }

    private boolean matches(CourseSearchDocument document, String keyword) {
        if (keyword.isBlank()) return true;
        return contains(document.getTitle(), keyword) || contains(document.getDescription(), keyword)
                || document.getTags().stream().anyMatch(tag -> contains(tag, keyword));
    }

    private SearchResult toResult(CourseSearchDocument document, String keyword) {
        double relevance = keyword.isBlank() ? 1 : 0;
        if (contains(document.getTitle(), keyword)) relevance += 3;
        if (contains(document.getDescription(), keyword)) relevance += 1;
        if (document.getTags().stream().anyMatch(tag -> contains(tag, keyword))) relevance += 2;
        double businessWeight = Math.log1p(Math.max(0, document.getLikeCount())) * 0.2;
        return new SearchResult(document.getId(), document.getTitle(), document.getDescription(),
                document.getTags(), document.getLikeCount(), relevance + businessWeight);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
