package com.learnhub.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.learnhub.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CourseSearchService {
    private final CourseSearchGateway gateway;

    public CourseSearchService(CourseSearchGateway gateway) { this.gateway = gateway; }

    public List<SearchResult> search(String keyword, Set<String> tags, int limit) {
        return searchPage(keyword, tags, 1, limit).records();
    }

    public SearchPage searchPage(String keyword, Set<String> tags, int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) page * size > 10000) {
            throw new BusinessException("SEARCH_PAGE_INVALID", "搜索分页范围无效，页大小为1至100，最多查询前10000条");
        }
        JsonNode result = gateway.search(query(keyword, tags, page, size));
        List<SearchResult> records = new ArrayList<>();
        for (JsonNode hit : result.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            Set<String> matchedTags = new LinkedHashSet<>();
            source.path("tags").forEach(tag -> matchedTags.add(tag.asText()));
            records.add(new SearchResult(Long.valueOf(source.path("id").asText()),
                    source.path("title").asText(), source.path("description").asText(""),
                    source.path("instructorName").asText(""), source.path("coverUrl").asText(""),
                    matchedTags, source.path("likeCount").asLong(), hit.path("_score").asDouble()));
        }
        return new SearchPage(records, result.path("hits").path("total").path("value").asLong(), page, size);
    }

    static Map<String, Object> query(String keyword, Set<String> tags, int page, int size) {
        String term = normalize(keyword, 200);
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("status", "PUBLISHED")));
        if (tags != null && !tags.isEmpty()) {
            if (tags.size() > 20) throw new BusinessException("SEARCH_TAGS_INVALID", "最多筛选20个标签");
            // 每个标签一个terms过滤条件，保持多选标签全部匹配的既有语义。
            for (String tag : tags) {
                String value = normalize(tag, 64);
                if (value.isBlank()) throw new BusinessException("SEARCH_TAGS_INVALID", "标签不能为空");
                filters.add(Map.of("terms", Map.of("tags", List.of(value))));
            }
        }
        Object textQuery = term.isBlank() ? Map.of("match_all", Map.of())
                : Map.of("multi_match", Map.of("query", term,
                        "fields", List.of("title^6", "instructorName^2", "tags^2", "description"),
                        "type", "best_fields"));
        return Map.of(
                "from", (page - 1) * size, "size", size, "track_total_hits", true,
                "query", Map.of("function_score", Map.of(
                        "query", Map.of("bool", Map.of("must", List.of(textQuery), "filter", filters)),
                        "field_value_factor", Map.of("field", "likeCount", "factor", 0.2, "modifier", "log1p", "missing", 0),
                        "max_boost", 1.0, "boost_mode", "sum")),
                "sort", List.of(Map.of("_score", "desc"), Map.of("id", "asc")));
    }

    public List<String> suggest(String prefix, int limit) {
        String term = normalize(prefix, 100);
        if (term.isBlank()) return List.of();
        if (limit < 1 || limit > 20) throw new BusinessException("SEARCH_SUGGEST_LIMIT_INVALID", "联想条数为1至20");
        JsonNode result = gateway.search(Map.of("size", 0,
                "suggest", Map.of("course_titles", Map.of("prefix", term,
                        "completion", Map.of("field", "suggest", "size", limit,
                                "skip_duplicates", true, "contexts", Map.of("publication", List.of("PUBLISHED")))))));
        Set<String> suggestions = new LinkedHashSet<>();
        for (JsonNode entry : result.path("suggest").path("course_titles")) {
            for (JsonNode option : entry.path("options")) {
                String text = option.path("text").asText();
                if (!text.isBlank()) suggestions.add(text);
            }
        }
        return suggestions.stream().limit(limit).toList();
    }

    private static String normalize(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new BusinessException("SEARCH_INPUT_INVALID", "搜索输入过长");
        return normalized;
    }
}
