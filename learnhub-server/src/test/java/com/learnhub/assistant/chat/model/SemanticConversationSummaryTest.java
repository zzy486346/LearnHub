package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticConversationSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Set<String>> allowed = Map.of(
            1L, Set.of("material:a", "progress:7"),
            2L, Set.of("material:b"));

    @Test void parsesStrictSummaryAndPreservesTraceability() {
        var summary = SemanticConversationSummary.parse(mapper, validJson(), allowed);

        assertThat(summary.schemaVersion()).isEqualTo(1);
        assertThat(summary.taskState().goal()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo("比较缓存方案并安排练习");
            assertThat(item.sourceMessageSeqs()).containsExactly(1L, 2L);
            assertThat(item.dependencyKeys()).containsExactly("material:a", "material:b");
            assertThat(item.inferred()).isFalse();
        });
        assertThat(summary.conversationSummary()).singleElement()
                .extracting(SemanticConversationSummary.Item::value).isEqualTo("用户否定了本地缓存方案");
        assertThat(summary.unresolvedReferences()).isEmpty();
    }

    @Test void rejectsUnknownSequenceOrDependencyOutsideReferencedUnion() {
        assertInvalid(validJson().replace("[1,2]", "[1,99]"));
        assertInvalid(validJson().replace("[\"material:a\",\"material:b\"]", "[\"material:unknown\",\"material:b\"]"));
    }

    @Test void rejectsExtraDuplicateMissingAndWrongTypedFields() {
        assertInvalid(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"));
        assertInvalid(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        assertInvalid(validJson().replace(",\"nextStep\":[]", ""));
        assertInvalid(validJson().replace("\"sourceMessageSeqs\":[1,2]", "\"sourceMessageSeqs\":[\"1\",2]"));
    }

    @Test void rejectsLinksInvalidUnicodeAndOversizedValuesOrArrays() {
        assertInvalid(validJson().replace("比较缓存方案并安排练习", "访问 https://example.com"));
        assertInvalid(validJson().replace("比较缓存方案并安排练习", "\\uD800"));
        assertInvalid(validJson().replace("比较缓存方案并安排练习", "甲".repeat(401)));
        String nineItems = "[" + "{\"value\":\"待办\",\"sourceMessageSeqs\":[1],\"dependencyKeys\":[],\"inferred\":false},".repeat(8)
                + "{\"value\":\"待办\",\"sourceMessageSeqs\":[1],\"dependencyKeys\":[],\"inferred\":false}]";
        assertInvalid(validJson().replace("\"openQuestions\":[]", "\"openQuestions\":" + nineItems));
    }

    @Test void structuralAcceptanceDoesNotClaimSemanticFaithfulness() {
        String structurallyValidButFalse = validJson().replace("用户否定了本地缓存方案", "用户已经完全掌握缓存一致性");
        var summary = SemanticConversationSummary.parse(mapper, structurallyValidButFalse, allowed);
        assertThat(summary.conversationSummary()).singleElement()
                .extracting(SemanticConversationSummary.Item::value).isEqualTo("用户已经完全掌握缓存一致性");
    }

    @Test void assistantOnlyProvenanceCannotBeClaimedAsExplicitUserState() {
        String assistantOnly = validJson().replace("[1,2]", "[2]")
                .replace("[\"material:a\",\"material:b\"]", "[\"material:b\"]");
        assertThatThrownBy(() -> SemanticConversationSummary.parse(mapper, assistantOnly, allowed, Set.of(1L)))
                .isInstanceOf(BusinessException.class);
        assertThat(SemanticConversationSummary.parse(mapper,
                assistantOnly.replace("\"inferred\":false", "\"inferred\":true"), allowed, Set.of(1L)).taskState().goal())
                .singleElement().extracting(SemanticConversationSummary.Item::inferred).isEqualTo(true);
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> SemanticConversationSummary.parse(mapper, json, allowed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_SUMMARY_INVALID"));
    }

    private static String validJson() {
        return """
                {"schemaVersion":1,"taskState":{
                  "goal":[{"value":"比较缓存方案并安排练习","sourceMessageSeqs":[1,2],"dependencyKeys":["material:a","material:b"],"inferred":false}],
                  "topic":[],"constraints":[],"decisions":[],"corrections":[],"openQuestions":[],"nextStep":[]},
                 "conversationSummary":[{"value":"用户否定了本地缓存方案","sourceMessageSeqs":[1],"dependencyKeys":["material:a"],"inferred":false}],
                 "unresolvedReferences":[]}
                """;
    }
}
