package com.learnhub.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "learnhub.it.enabled", matches = "true")
class CourseSearchInfrastructureIntegrationTest {
    private RestClient client;
    private CourseSearchGateway gateway;
    private CourseSearchService service;
    private final List<String> indexes = new ArrayList<>();
    private String index;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        client = RestClient.builder(HttpHost.create("http://localhost:9200")).build();
        ObjectProvider<RestClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        gateway = new CourseSearchGateway(provider, new ObjectMapper().findAndRegisterModules(),
                "learnhub-search-it-" + UUID.randomUUID().toString().replace("-", ""), "standard", "standard");
        service = new CourseSearchService(gateway);
        assertFalse(gateway.ensureAlias());
        index = gateway.createVersionedIndex();
        indexes.add(index);
        gateway.swapAlias(index);
    }

    @AfterEach
    void cleanup() throws Exception {
        try { if (gateway != null) for (String ownedIndex : indexes) {
            if (gateway.isAliasTarget(ownedIndex)) gateway.removeAlias(ownedIndex);
            gateway.deleteIndex(ownedIndex);
        } }
        finally { if (client != null) client.close(); }
    }

    @Test
    void realQueriesHonorBm25TagsPublishedStateAndBoundedPopularity() {
        gateway.upsert(document(1L, "Java concurrency", "thread basics", Set.of("Java", "架构"), 0, "PUBLISHED"));
        gateway.upsert(document(2L, "Database overview", "Java concurrency", Set.of("Java"), 1000000000L, "PUBLISHED"));
        gateway.upsert(document(3L, "Java concurrency", "thread basics", Set.of("Java", "架构"), 100, "PUBLISHED"));
        gateway.upsert(document(4L, "Java concurrency", "thread basics", Set.of("Java"), 100, "DRAFT"));
        gateway.refresh(index);
        var page = service.searchPage("Java concurrency", Set.of(), 1, 20);
        assertEquals(3, page.total());
        assertEquals(3L, page.records().get(0).id());
        assertEquals(1L, page.records().get(1).id());
        assertEquals(2L, page.records().get(2).id());
        assertEquals(2, service.searchPage("Java", Set.of("Java", "架构"), 1, 20).total());
        assertEquals(1, service.searchPage("Java", Set.of(), 2, 1).records().size());
        assertEquals(List.of("Java concurrency"), service.suggest("Java con", 10));
        assertTrue(service.suggest("Java", 10).contains("Java"));
        assertTrue(service.suggest(" ", 10).isEmpty());
        var mapping = gateway.request("GET", "/" + index + "/_mapping", null, false);
        assertEquals("completion", mapping.path(index).path("mappings").path("properties").path("suggest").path("type").asText());
    }

    @Test
    void aliasSwitchPreservesOldIndexAndMovesSearchToNewVersion() {
        gateway.upsert(document(1L, "Old course", "", Set.of(), 0, "PUBLISHED"));
        gateway.refresh(index);
        String replacement = gateway.createVersionedIndex();
        indexes.add(replacement);
        gateway.index(replacement, document(2L, "New course", "", Set.of(), 0, "PUBLISHED"));
        gateway.refresh(replacement);
        assertEquals(1L, service.search("", Set.of(), 20).get(0).id());
        gateway.swapAlias(replacement);
        assertEquals(2L, service.search("", Set.of(), 20).get(0).id());
        assertNotNull(gateway.request("GET", "/" + index, null, false));
        gateway.delete(2L);
        gateway.refresh(replacement);
        assertTrue(service.search("", Set.of(), 20).isEmpty());
    }

    private CourseSearchDocument document(Long id, String title, String description, Set<String> tags, long likes, String status) {
        var doc = new CourseSearchDocument(id, title, description, "Teacher", "", tags, likes);
        doc.setStatus(status);
        doc.setPublishedAt(java.time.Instant.now());
        return doc;
    }
}
