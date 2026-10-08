package com.learnhub.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CourseSearchGateway {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String alias;
    private final String analyzer;
    private final String searchAnalyzer;

    public CourseSearchGateway(ObjectProvider<RestClient> clients, ObjectMapper mapper,
            @Value("${learnhub.search.alias:learnhub-courses-search}") String alias,
            @Value("${learnhub.search.analyzer:ik_max_word}") String analyzer,
            @Value("${learnhub.search.search-analyzer:ik_smart}") String searchAnalyzer) {
        if (!alias.matches("[a-z][a-z0-9-]{0,100}")) throw new IllegalArgumentException("Invalid search alias");
        this.client = clients.getIfAvailable();
        this.mapper = mapper;
        this.alias = alias;
        this.analyzer = analyzer;
        this.searchAnalyzer = searchAnalyzer;
    }

    public String aliasName() { return alias; }

    public JsonNode search(Map<String, Object> query) {
        JsonNode result = request("POST", "/" + alias + "/_search", query, false);
        if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() > 0) {
            throw new SearchUnavailableException(new IOException("Elasticsearch returned incomplete results"));
        }
        return result;
    }

    public boolean ensureAlias() {
        return request("GET", "/_alias/" + alias, null, true) != null;
    }

    public String createVersionedIndex() {
        String name = alias + "-v1-" + UUID.randomUUID().toString().replace("-", "");
        try (var input = new ClassPathResource("elasticsearch/courses-v1.json").getInputStream()) {
            ObjectNode mapping = (ObjectNode) mapper.readTree(input);
            JsonNode properties = mapping.path("mappings").path("properties");
            for (String field : List.of("title", "description", "instructorName", "suggest")) {
                ((ObjectNode) properties.path(field)).put("analyzer", analyzer).put("search_analyzer", searchAnalyzer);
            }
            request("PUT", "/" + name, mapping, false);
            return name;
        } catch (IOException error) {
            throw new SearchUnavailableException(error);
        }
    }

    public void upsert(CourseSearchDocument document) { index(alias, document); }

    public void index(String index, CourseSearchDocument document) {
        validateIndex(index);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", document.getId().toString());
        source.put("title", document.getTitle());
        source.put("description", document.getDescription());
        source.put("instructorName", document.getInstructor());
        source.put("coverUrl", document.getCoverUrl());
        source.put("tags", document.getTags() == null ? Set.of() : document.getTags());
        source.put("likeCount", Math.max(0, document.getLikeCount()));
        source.put("status", document.getStatus());
        if (document.getPublishedAt() != null) source.put("publishedAt", document.getPublishedAt().toString());
        Set<String> inputs = new LinkedHashSet<>();
        for (String value : List.of(document.getTitle() == null ? "" : document.getTitle(),
                document.getInstructor() == null ? "" : document.getInstructor())) {
            if (!value.isBlank()) inputs.add(value.substring(0, Math.min(value.length(), 100)));
        }
        if (document.getTags() != null) for (String tag : document.getTags()) {
            if (tag != null && !tag.isBlank()) inputs.add(tag.substring(0, Math.min(tag.length(), 100)));
        }
        source.put("suggest", Map.of("input", inputs));
        request("PUT", "/" + index + "/_doc/" + document.getId(), source, false);
    }

    public void delete(Long courseId) { request("DELETE", "/" + alias + "/_doc/" + courseId, null, true); }

    public void refresh(String index) { validateIndex(index); request("POST", "/" + index + "/_refresh", null, false); }

    public void swapAlias(String newIndex) {
        if (!newIndex.startsWith(alias + "-v1-")) throw new IllegalArgumentException("Invalid rebuilt index");
        JsonNode existing = request("GET", "/_alias/" + alias, null, true);
        List<Object> actions = new ArrayList<>();
        if (existing != null) existing.fieldNames().forEachRemaining(index ->
                actions.add(Map.of("remove", Map.of("index", index, "alias", alias, "must_exist", true))));
        actions.add(Map.of("add", Map.of("index", newIndex, "alias", alias, "is_write_index", true)));
        JsonNode response = request("POST", "/_aliases", Map.of("actions", actions), false);
        if (!response.path("acknowledged").asBoolean()) throw new SearchUnavailableException(new IOException("Alias switch not acknowledged"));
    }

    public void deleteIndex(String index) {
        if (!index.startsWith(alias + "-v1-")) throw new IllegalArgumentException("Refusing unrelated index deletion");
        if (isAliasTarget(index)) throw new IllegalStateException("Refusing to delete the active search index");
        request("DELETE", "/" + index, null, true);
    }

    public boolean isAliasTarget(String index) {
        JsonNode current = request("GET", "/_alias/" + alias, null, true);
        return current != null && current.has(index);
    }

    public void removeAlias(String index) {
        validateIndex(index);
        request("POST", "/_aliases", Map.of("actions", List.of(Map.of("remove",
                Map.of("index", index, "alias", alias, "must_exist", true)))), false);
    }

    private void validateIndex(String index) {
        if (!index.equals(alias) && !index.matches(java.util.regex.Pattern.quote(alias) + "-v1-[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Invalid search index");
        }
    }

    JsonNode request(String method, String path, Object body, boolean allowMissing) {
        if (client == null) throw new SearchUnavailableException(new IOException("Elasticsearch client unavailable"));
        try {
            Request request = new Request(method, path);
            if (body != null) request.setJsonEntity(mapper.writeValueAsString(body));
            var response = client.performRequest(request);
            return mapper.readTree(EntityUtils.toString(response.getEntity()));
        } catch (ResponseException error) {
            if (allowMissing && error.getResponse().getStatusLine().getStatusCode() == 404) return null;
            throw new SearchUnavailableException(error);
        } catch (IOException | RuntimeException error) {
            if (error instanceof SearchUnavailableException unavailable) throw unavailable;
            throw new SearchUnavailableException(error);
        }
    }
}
