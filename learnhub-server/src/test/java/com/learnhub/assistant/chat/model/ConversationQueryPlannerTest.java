package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationQueryPlannerTest {
    @Test
    void independentQuestionDoesNotInheritHistory() {
        var plan = ConversationQueryPlanner.plan("Redis 是什么？", List.of("缓存如何失效？"));
        assertThat(plan.query()).isEqualTo("Redis 是什么？");
        assertThat(plan.mode()).isEqualTo(ConversationQueryPlanner.Mode.INDEPENDENT);
    }

    @Test
    void referentialQuestionUsesLatestClearSubjectAndSkipsShortReferences() {
        var plan = ConversationQueryPlanner.plan("刚才的方法有什么局限？",
                List.of("热点缓存失效后怎样防止重建？", "这个怎么做？", "再讲一次。"));
        assertThat(plan.query()).isEqualTo("刚才的方法有什么局限？\n热点缓存失效后怎样防止重建？");
        assertThat(plan.mode()).isEqualTo(ConversationQueryPlanner.Mode.REFERENTIAL);
    }

    @Test
    void unresolvedReferenceRemainsCurrentQuestionOnly() {
        var plan = ConversationQueryPlanner.plan("继续。", List.of("这个呢？", "再讲一次"));
        assertThat(plan.query()).isEqualTo("继续。");
        assertThat(plan.mode()).isEqualTo(ConversationQueryPlanner.Mode.REFERENTIAL);
    }

    @Test
    void capUsesUnicodeCodePointsAndDoesNotSplitSurrogatePair() {
        String question = "接着说明" + "😀".repeat(600);
        var plan = ConversationQueryPlanner.plan(question, List.of("主体"));
        assertThat(plan.query().codePointCount(0, plan.query().length())).isEqualTo(500);
        assertThat(plan.query().charAt(plan.query().length() - 1)).isNotEqualTo('\uD83D');
    }

    @Test
    void malformedUnicodeAndBlankQuestionsAreRejected() {
        assertThatThrownBy(() -> ConversationQueryPlanner.plan(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationQueryPlanner.plan("坏\uD800", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
