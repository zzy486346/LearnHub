package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatContextPlannerTest {
    private final ChatContextPlanner planner = new ChatContextPlanner(new ObjectMapper());

    @Test void reservesOutputAndPreviouslyReservedCallsConservatively() {
        assertThat(planner.capacity(16000, 24000, 10000, 1200)).isEqualTo(12800);
        assertThat(planner.capacity(16000, 24000, 23999, 1200)).isZero();
        assertThat(planner.historyAllowance(16000, 8000)).isEqualTo(4000);
    }

    @Test void dropsWholeOldestPairsAndRetainsCurrentTaskAndRules() {
        var system = SystemMessage.from("规则");
        var current = UserMessage.from("当前问题");
        List<ChatMessage> base = List.of(system, UserMessage.from("最近问题"), AiMessage.from("最近回答"), current);
        List<ChatMessage> full = List.of(system, UserMessage.from("旧问题".repeat(500)),
                AiMessage.from("旧回答"), base.get(1), base.get(2), current);
        var plan = planner.fit(full, null, 16000, planner.bytes(base, null));
        assertThat(plan.messages()).containsExactlyElementsOf(base);
        assertThat(plan.droppedRounds()).isEqualTo(1);
    }

    @Test void neverTruncatesCurrentJsonOrToolChainToFit() {
        var call = ToolExecutionRequest.builder().id("a").name("get_course_outline").arguments("{}").build();
        List<ChatMessage> messages = List.of(SystemMessage.from("规则"), UserMessage.from("{\"question\":\"当前问题\"}"),
                AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"chapters\":\"" + "很大".repeat(3000) + "\"}"));
        assertThatThrownBy(() -> planner.fit(messages, Map.of("schema", "工具"), 5000, 5000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("AI_MODEL_INPUT_TOO_LARGE"));
        assertThat(messages).hasSize(4);
    }

    @Test void reportsRunBudgetSeparatelyFromInputLimitAndCountsSchema() {
        List<ChatMessage> messages = List.of(UserMessage.from("当前问题"));
        assertThat(planner.bytes(messages, Map.of("schema", "参数"))).isGreaterThan(planner.bytes(messages, null));
        assertThatThrownBy(() -> planner.fit(messages, null, 16000, 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ASSISTANT_BUDGET_EXCEEDED"));
    }
}
