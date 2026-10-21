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
        assertThat(com.learnhub.assistant.runtime.AssistantRunGuard.remainingInputReservation(24000, 10000, 1200)).isEqualTo(12800);
        assertThat(com.learnhub.assistant.runtime.AssistantRunGuard.remainingInputReservation(24000, 23999, 1200)).isZero();
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

    @Test void dropsOnlyOldHistoryAndKeepsMultipleToolGroupsInExactOrder() {
        var outline = ToolExecutionRequest.builder().id("outline-1").name("get_course_outline")
                .arguments("{\"courseId\":\"7\"}").build();
        var progress = ToolExecutionRequest.builder().id("progress-1").name("get_current_progress")
                .arguments("{\"courseId\":\"7\"}").build();
        List<ChatMessage> required = List.of(
                SystemMessage.from("规则"),
                UserMessage.from("{\"question\":\"结合目录和进度安排练习\"}"),
                AiMessage.from(outline),
                ToolExecutionResultMessage.from(outline, "{\"chapters\":[{\"id\":1}]}"),
                AiMessage.from(progress),
                ToolExecutionResultMessage.from(progress, "{\"completed\":[1],\"next\":2}"));
        List<ChatMessage> withOldHistory = new java.util.ArrayList<>();
        withOldHistory.add(required.get(0));
        withOldHistory.add(UserMessage.from("很早的问题".repeat(600)));
        withOldHistory.add(AiMessage.from("很早的回答"));
        withOldHistory.addAll(required.subList(1, required.size()));

        var plan = planner.fit(withOldHistory, Map.of("schema", "四个只读工具"), 20000,
                planner.bytes(required, Map.of("schema", "四个只读工具")));

        assertThat(plan.messages()).containsExactlyElementsOf(required);
        assertThat(plan.droppedRounds()).isOne();
        assertThat(plan.droppedSummaryEntries()).isZero();
    }

    @Test void remeasuresWhenRulesEvidenceOrToolSchemaChanges() {
        List<ChatMessage> base = List.of(SystemMessage.from("规则-v1"),
                UserMessage.from("{\"question\":\"当前问题\",\"evidence\":[]}"));
        List<ChatMessage> changedRules = List.of(SystemMessage.from("规则-v2-增加输出约束"), base.get(1));
        List<ChatMessage> changedEvidence = List.of(base.get(0),
                UserMessage.from("{\"question\":\"当前问题\",\"evidence\":[{\"id\":\"E1\"}]}"));

        int original = planner.bytes(base, Map.of("schema", "outline"));
        assertThat(planner.bytes(changedRules, Map.of("schema", "outline"))).isNotEqualTo(original);
        assertThat(planner.bytes(changedEvidence, Map.of("schema", "outline"))).isNotEqualTo(original);
        assertThat(planner.bytes(base, Map.of("schema", "outline-and-progress"))).isNotEqualTo(original);
    }

    @Test void reportsRunBudgetSeparatelyFromInputLimitAndCountsSchema() {
        List<ChatMessage> messages = List.of(UserMessage.from("当前问题"));
        assertThat(planner.bytes(messages, Map.of("schema", "参数"))).isGreaterThan(planner.bytes(messages, null));
        assertThatThrownBy(() -> planner.fit(messages, null, 16000, 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ASSISTANT_BUDGET_EXCEEDED"));
    }

    @Test void dropsOlderSummaryEntriesBeforeRecentCompleteRoundsAndKeepsValidJson() throws Exception {
        var json = new ObjectMapper();
        String required = "{\"question\":\"当前\",\"evidence\":[],\"conversationSummary\":{\"userStatements\":[]}}";
        String withSummary = required.replace("\"userStatements\":[]", "\"userStatements\":[{\"text\":\"" + "旧摘录".repeat(500) + "\"}]");
        List<ChatMessage> base = List.of(UserMessage.from("最近问题"), AiMessage.from("最近回答"), UserMessage.from(required));
        var plan = planner.fit(List.of(base.get(0), base.get(1), UserMessage.from(withSummary)), null,
                16000, planner.bytes(base, null));
        assertThat(plan.droppedRounds()).isZero();
        assertThat(plan.droppedSummaryEntries()).isOne();
        assertThat(json.readTree(((UserMessage) plan.messages().get(2)).singleText()).path("conversationSummary")
                .path("userStatements")).isEmpty();
    }
}
