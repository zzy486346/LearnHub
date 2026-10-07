package com.learnhub.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class CourseSearchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queryUsesNativeBm25BoundedBusinessWeightAndEveryTagFilter() throws Exception {
        var query = mapper.valueToTree(CourseSearchService.query("Java", Set.of("Java", "架构"), 2, 12));
        assertEquals(12, query.path("from").asInt());
        assertEquals(12, query.path("size").asInt());
        var function = query.path("query").path("function_score");
        assertEquals("sum", function.path("boost_mode").asText());
        assertEquals(1.0, function.path("max_boost").asDouble());
        assertEquals("log1p", function.path("field_value_factor").path("modifier").asText());
        assertEquals("Java", function.path("query").path("bool").path("must").get(0).path("multi_match").path("query").asText());
        assertEquals(3, function.path("query").path("bool").path("filter").size());
        assertEquals("PUBLISHED", function.path("query").path("bool").path("filter").get(0).path("term").path("status").asText());
    }

    @Test
    void searchReadsOnlyReturnedPageAndPreservesNativeScores() throws Exception {
        CourseSearchGateway gateway = mock(CourseSearchGateway.class);
        when(gateway.search(anyMap())).thenReturn(mapper.readTree("""
                {"hits":{"total":{"value":25},"hits":[{"_score":8.25,"_source":{
                "id":"1002","title":"Java","description":"课程","instructorName":"老师","coverUrl":"",
                "tags":["Java"],"likeCount":20}}]}}
                """));
        SearchPage page = new CourseSearchService(gateway).searchPage("Java", Set.of(), 2, 12);
        assertEquals(25, page.total());
        assertEquals(2, page.current());
        assertEquals(8.25, page.records().get(0).score());
        assertEquals("老师", page.records().get(0).instructor());
        verify(gateway, times(1)).search(anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void suggestionsUseCompletionAndPublicationContextNotPrefixScan() throws Exception {
        CourseSearchGateway gateway = mock(CourseSearchGateway.class);
        when(gateway.search(anyMap())).thenReturn(mapper.readTree("""
                {"suggest":{"course_titles":[{"options":[{"text":"Java课程"},{"text":"Java课程"},{"text":"Java实战"}]}]}}
                """));
        var service = new CourseSearchService(gateway);
        assertEquals(List.of(), service.suggest(" ", 10));
        verifyNoInteractions(gateway);
        assertEquals(List.of("Java课程", "Java实战"), service.suggest("Java", 2));
        ArgumentCaptor<java.util.Map<String, Object>> query = ArgumentCaptor.forClass(java.util.Map.class);
        verify(gateway).search(query.capture());
        var completion = mapper.valueToTree(query.getValue()).path("suggest").path("course_titles").path("completion");
        assertEquals("suggest", completion.path("field").asText());
        assertTrue(completion.path("skip_duplicates").asBoolean());
        assertEquals("PUBLISHED", completion.path("contexts").path("publication").get(0).asText());
    }

    @Test
    void invalidPageAndInputFailBeforeCallingElasticsearch() {
        CourseSearchGateway gateway = mock(CourseSearchGateway.class);
        var service = new CourseSearchService(gateway);
        assertThrows(BusinessException.class, () -> service.searchPage("Java", Set.of(), 101, 100));
        assertThrows(BusinessException.class, () -> service.searchPage("Java", Set.of(), 0, 20));
        assertThrows(BusinessException.class, () -> service.suggest("Java", 21));
        assertThrows(BusinessException.class, () -> service.searchPage("a".repeat(201), Set.of(), 1, 20));
        verifyNoInteractions(gateway);
    }

    @Test
    void unavailableElasticsearchNeverFallsBackToMemoryOrDatabase() {
        CourseSearchGateway gateway = mock(CourseSearchGateway.class);
        when(gateway.search(anyMap())).thenThrow(new SearchUnavailableException(new java.io.IOException("offline")));
        assertThrows(SearchUnavailableException.class, () -> new CourseSearchService(gateway).search("", Set.of(), 20));
    }
}
