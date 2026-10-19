package com.learnhub.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.runtime.DeadlineJdbcTemplate;
import com.learnhub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatToolExecutionStoreTest {
    private final DeadlineJdbcTemplate jdbc = mock(DeadlineJdbcTemplate.class);
    private final ChatToolExecutionStore store = new ChatToolExecutionStore(jdbc);
    private final ChatPersistenceService.Run run = new ChatPersistenceService.Run(
            "run-1", "conversation-1", 7, "request-1", "RUNNING", "lease-1", "问题");
    private final ToolCall call = new ToolCall("call_1", "get_course_outline", "{\"private\":\"not-stored\"}");

    @Test
    void writesOnlyHashAndServerOwnedIdentity() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String id = store.begin(7, run, 1, call, "a".repeat(64));
        assertThat(id).matches("[a-f0-9-]{36}");
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()).contains("a".repeat(64), "run-1", 7L, "lease-1")
                .doesNotContain(call.arguments());
    }

    @Test
    void zeroRowInsertAndTerminalUpdatesFailClosed() {
        assertCancelled(() -> store.begin(7, run, 1, call, "a".repeat(64)));
        assertCancelled(() -> store.complete(7, run, "execution-1", 10));
        assertCancelled(() -> store.fail(7, run, "execution-1", 10, "TOOL_FAILED", false));
    }

    @Test
    void rejectsUnsafeIdentifiersHashesNamesOrdersAndForeignOwnerBeforeJdbc() {
        // 构造时仅设置 queryTimeout，清空后验证输入拒绝不访问数据库。
        org.mockito.Mockito.clearInvocations(jdbc);
        assertInvalid(() -> store.begin(8, run, 1, call, "a".repeat(64)));
        assertInvalid(() -> store.begin(7, run, 0, call, "a".repeat(64)));
        assertInvalid(() -> store.begin(7, run, 33, call, "a".repeat(64)));
        assertInvalid(() -> store.begin(7, run, 1, call, "raw private arguments"));
        assertInvalid(() -> store.begin(7, run, 1, new ToolCall("bad id", call.name(), "{}"), "a".repeat(64)));
        assertInvalid(() -> store.begin(7, run, 1, new ToolCall("call", "delete_course", "{}"), "a".repeat(64)));
        assertInvalid(() -> store.complete(7, run, "execution-1", -1));
        assertInvalid(() -> store.fail(7, run, "execution-1", 1, "private exception body", false));
        verifyNoInteractions(jdbc);
    }

    private void assertInvalid(Runnable action) { assertCode("CHAT_INPUT_INVALID", action); }
    private void assertCancelled(Runnable action) { assertCode("ASSISTANT_CANCELLED", action); }
    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
