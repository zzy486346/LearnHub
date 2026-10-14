package com.learnhub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CourseSearchGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void aliasIsCompatibleOnlyWhenEveryTargetHasLongCategoryIdMapping() throws Exception {
        ObjectProvider<RestClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(null);
        CourseSearchGateway gateway = org.mockito.Mockito.spy(new CourseSearchGateway(
                clients, mapper, "learnhub-courses-search", "ik_max_word", "ik_smart"));
        var aliases = mapper.readTree("{\"courses-v1\":{\"aliases\":{\"learnhub-courses-search\":{}}}}}");
        doReturn(aliases).when(gateway).request("GET", "/_alias/learnhub-courses-search", null, true);
        doReturn(mapper.readTree("{\"courses-v1\":{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}}"))
                .when(gateway).request("GET", "/learnhub-courses-search/_mapping", null, false);
        assertThat(gateway.ensureAlias()).isFalse();

        doReturn(mapper.readTree("{\"courses-v1\":{\"mappings\":{\"properties\":{\"categoryId\":{\"type\":\"long\"}}}}}"))
                .when(gateway).request("GET", "/learnhub-courses-search/_mapping", null, false);
        assertThat(gateway.ensureAlias()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAliasIsIncompatibleWithoutMappingLookup() {
        ObjectProvider<RestClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(null);
        CourseSearchGateway gateway = org.mockito.Mockito.spy(new CourseSearchGateway(
                clients, mapper, "learnhub-courses-search", "ik_max_word", "ik_smart"));
        doReturn(null).when(gateway).request("GET", "/_alias/learnhub-courses-search", null, true);

        assertThat(gateway.ensureAlias()).isFalse();
    }
}
