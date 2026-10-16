package com.learnhub.assistant.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.search.SearchUnavailableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.config.RequestConfig;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 知识块专用投影；查询仅取来源标识，响应正文由 MySQL 提供。 */
@Component
public class KnowledgeSearchGateway {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String alias;
    private final String analyzer;
    private final String searchAnalyzer;

    public KnowledgeSearchGateway(ObjectProvider<RestClient> clients, ObjectMapper mapper,
            @Value("${learnhub.knowledge.alias:learnhub-knowledge-search}") String alias,
            @Value("${learnhub.search.analyzer:ik_max_word}") String analyzer,
            @Value("${learnhub.search.search-analyzer:ik_smart}") String searchAnalyzer) {
        if (!alias.matches("[a-z][a-z0-9-]{0,80}") || !alias.contains("knowledge")) {
            throw new IllegalArgumentException("知识索引别名必须包含 knowledge，且只能使用小写字母、数字和连字符");
        }
        this.client = clients.getIfAvailable(); this.mapper = mapper; this.alias = alias;
        this.analyzer = analyzer; this.searchAnalyzer = searchAnalyzer;
    }

    /** 返回 true 表示别名刚恢复，需要从事实源重新补齐所有材料。 */
    public boolean ensureAlias() {
        if (request("GET", "/_alias/" + alias, null, true) != null) return false;
        try {
            request("PUT", "/" + indexName(), mapping(), false);
        } catch (SearchUnavailableException error) {
            if (!(error.getCause() instanceof ResponseException response)
                    || response.getResponse().getStatusLine().getStatusCode() != 400
                    || !error.getCause().getMessage().contains("resource_already_exists_exception")) throw error;
        }
        JsonNode result = request("POST", "/_aliases", Map.of("actions", List.of(
                Map.of("add", Map.of("index", indexName(), "alias", alias, "is_write_index", true)))), false);
        if (!result.path("acknowledged").asBoolean()) throw unavailable();
        return true;
    }

    public void index(KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk) {
        if (document.id() != chunk.documentId() || document.sourceVersion() != chunk.sourceVersion()) {
            throw new IllegalArgumentException("知识块版本与文档不一致");
        }
        request("PUT", "/" + alias + "/_doc/" + document.id() + "-" + document.sourceVersion()
                + "-" + chunk.chunkOrder(), Map.of(
                "chunkId", chunk.id(), "documentId", document.id(), "sourceVersion", document.sourceVersion(),
                "courseId", document.courseId(), "lessonId", document.lessonId(),
                "title", document.title(), "heading", chunk.heading() == null ? "" : chunk.heading(), "content", chunk.content()), false);
    }

    public void removeOtherVersions(long documentId, long currentVersion) {
        var filter = Map.of("term", Map.of("documentId", documentId));
        var older = Map.of("range", Map.of("sourceVersion", Map.of("lt", currentVersion)));
        JsonNode result = request("POST", "/" + alias + "/_delete_by_query?conflicts=proceed",
                Map.of("query", Map.of("bool", Map.of("filter", List.of(filter, older)))), false);
        if (result.path("timed_out").asBoolean() || !result.path("failures").isEmpty()) throw unavailable();
    }

    public void refresh() {
        var result = request("POST", "/" + alias + "/_refresh", null, false);
        if (result.path("_shards").path("failed").asInt() > 0) throw unavailable();
    }

    public List<Hit> search(long courseId, List<Long> lessonIds, String query, int limit) {
        if (lessonIds.isEmpty()) return List.of();
        JsonNode result = request("POST", "/" + alias + "/_search", query(courseId, lessonIds, query, limit), false);
        if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() > 0) throw unavailable();
        List<Hit> hits = new ArrayList<>();
        for (JsonNode hit : result.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new Hit(source.path("chunkId").asLong(), source.path("documentId").asLong(),
                    source.path("sourceVersion").asLong(), hit.path("_score").asDouble()));
        }
        return hits;
    }

    static Map<String, Object> query(long courseId, List<Long> lessonIds, String question, int limit) {
        if (courseId <= 0 || limit < 1 || limit > 100 || question == null || question.isBlank()
                || question.length() > 500 || lessonIds.size() > 10000) throw new IllegalArgumentException("检索参数超出范围");
        return Map.of("size", limit, "_source", List.of("chunkId", "documentId", "sourceVersion"),
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("multi_match", Map.of("query", question,
                                "fields", List.of("title^2", "heading^3", "content")))),
                        "filter", List.of(Map.of("term", Map.of("courseId", courseId)),
                                Map.of("terms", Map.of("lessonId", lessonIds))))),
                "sort", List.of(Map.of("_score", "desc"), Map.of("chunkId", "asc")));
    }

    private Map<String, Object> mapping() {
        Map<String, Object> text = Map.of("type", "text", "analyzer", analyzer, "search_analyzer", searchAnalyzer);
        return Map.of("mappings", Map.of("dynamic", "strict", "properties", Map.of(
                "chunkId", Map.of("type", "long"), "documentId", Map.of("type", "long"),
                "sourceVersion", Map.of("type", "long"), "courseId", Map.of("type", "long"),
                "lessonId", Map.of("type", "long"), "title", text, "heading", text, "content", text)));
    }

    private String indexName() { return alias + "-v1"; }

    public String aliasName() { return alias; }

    // 仅供隔离测试清理；生产重建不删除现有索引。
    public void deleteOwnedIndex() {
        if (!alias.startsWith("knowledge-it-")) throw new IllegalStateException("只允许删除知识集成测试索引");
        request("DELETE", "/" + indexName(), null, true);
    }

    private JsonNode request(String method, String path, Object body, boolean allowMissing) {
        if (client == null) throw unavailable();
        try {
            Request request = new Request(method, path);
            request.setOptions(RequestOptions.DEFAULT.toBuilder().setRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(3000).setSocketTimeout(5000).setConnectionRequestTimeout(3000).build()).build());
            if (body != null) request.setJsonEntity(mapper.writeValueAsString(body));
            var response = com.learnhub.assistant.runtime.AssistantSearchIo.perform(client, request);
            return mapper.readTree(EntityUtils.toString(response.getEntity()));
        } catch (ResponseException error) {
            if (allowMissing && error.getResponse().getStatusLine().getStatusCode() == 404) return null;
            throw new SearchUnavailableException(error);
        } catch (IOException error) { throw new SearchUnavailableException(error); }
    }

    private SearchUnavailableException unavailable() {
        return new SearchUnavailableException(new IOException("知识检索服务暂时不可用"));
    }

    public record Hit(long chunkId, long documentId, long sourceVersion, double score) {}
}
