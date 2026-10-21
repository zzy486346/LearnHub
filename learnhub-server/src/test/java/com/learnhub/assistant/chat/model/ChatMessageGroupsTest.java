package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageGroupsTest {

    @Test void preservesSystemsPlainHistoryAndCurrentUserInOrder() {
        List<ChatMessage> messages = List.of(SystemMessage.from("规则"),
                UserMessage.from("旧问题"), AiMessage.from("旧回答"), UserMessage.from("当前问题"));

        var groups = ChatMessageGroups.groups(messages);

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).messages()).containsExactly(messages.get(0));
        assertThat(groups.get(0).current()).isFalse();
        assertThat(groups.get(0).plainRound()).isFalse();
        assertThat(groups.get(1).messages()).containsExactly(messages.get(1), messages.get(2));
        assertThat(groups.get(1).current()).isFalse();
        assertThat(groups.get(1).plainRound()).isTrue();
        assertThat(groups.get(2).messages()).containsExactly(messages.get(3));
        assertThat(groups.get(2).current()).isTrue();
        assertThat(groups.get(2).plainRound()).isFalse();
    }

    @Test void keepsACompleteMultiToolChainInOneCurrentGroup() {
        var outline = call("outline-1", "get_course_outline");
        var progress = call("progress-1", "get_current_progress");
        List<ChatMessage> messages = List.of(UserMessage.from("结合目录和进度安排练习"),
                AiMessage.from(List.of(outline, progress)),
                ToolExecutionResultMessage.from(progress, "{\"completed\":1}"),
                ToolExecutionResultMessage.from(outline, "{\"chapters\":2}"));

        var groups = ChatMessageGroups.groups(messages);

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.messages()).containsExactlyElementsOf(messages);
            assertThat(group.current()).isTrue();
            assertThat(group.plainRound()).isFalse();
        });
    }

    @Test void completedToolChainBecomesHistoryWhenNextUserStarts() {
        var outline = call("outline-1", "get_course_outline");
        List<ChatMessage> toolRound = List.of(UserMessage.from("查目录"), AiMessage.from(outline),
                ToolExecutionResultMessage.from(outline, "{}"), AiMessage.from("目录回答"));
        List<ChatMessage> messages = new java.util.ArrayList<>(toolRound);
        messages.add(UserMessage.from("继续第二步"));

        var groups = ChatMessageGroups.groups(messages);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).messages()).containsExactlyElementsOf(toolRound);
        assertThat(groups.get(0).current()).isFalse();
        assertThat(groups.get(0).plainRound()).isFalse();
        assertThat(groups.get(1).messages()).containsExactly(messages.get(messages.size() - 1));
        assertThat(groups.get(1).current()).isTrue();
    }

    @Test void rejectsOrphanMismatchedAndDuplicateResults() {
        var call = call("call-1", "get_course_outline");
        assertInvalid(List.of(ToolExecutionResultMessage.from(call, "{}")));
        assertInvalid(List.of(UserMessage.from("问题"), AiMessage.from(call),
                ToolExecutionResultMessage.from("call-1", "get_current_progress", "{}")));
        assertInvalid(List.of(UserMessage.from("问题"), AiMessage.from(call),
                ToolExecutionResultMessage.from(call, "{}"), ToolExecutionResultMessage.from(call, "{}")));
    }

    @Test void rejectsIncompleteToolBatchAndOrphanAssistant() {
        var first = call("call-1", "get_course_outline");
        var second = call("call-2", "get_current_progress");
        assertInvalid(List.of(UserMessage.from("问题"), AiMessage.from(List.of(first, second)),
                ToolExecutionResultMessage.from(first, "{}")));
        assertInvalid(List.of(AiMessage.from("没有用户问题")));
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static void assertInvalid(List<ChatMessage> messages) {
        assertThatThrownBy(() -> ChatMessageGroups.groups(messages))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_CONTEXT_INVALID"));
    }
}
