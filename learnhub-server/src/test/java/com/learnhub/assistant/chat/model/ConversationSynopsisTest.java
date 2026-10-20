package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConversationSynopsisTest {
    @Test void extractsOnlyAttributedUserWordsAndTracksExactMessageAndDependencyIdentity() {
        var entry = ConversationSynopsis.extract(1, 2, "我想先理解重试边界", "助手推断用户已掌握全部内容", "依赖v1", "scope-a");
        assertThat(entry.text()).isEqualTo("我想先理解重试边界");
        assertThat(entry.kind()).isEqualTo("USER_QUOTE");
        assertThat(entry.truncated()).isFalse();
        assertThat(entry.messageHash()).hasSize(64);
        assertThat(entry.messageHash()).isNotEqualTo(ConversationSynopsis.extract(1, 2, entry.text(),
                "另一回答", "依赖v1", "scope-a").messageHash());
        assertThat(entry.messageHash()).isNotEqualTo(ConversationSynopsis.extract(1, 2, entry.text(),
                "助手推断用户已掌握全部内容", "依赖v1", "scope-b").messageHash());
        assertThat(entry.dependencyHash()).isNotEqualTo(ConversationSynopsis.extract(1, 2, entry.text(),
                "助手推断用户已掌握全部内容", "依赖v2", "scope-a").dependencyHash());
    }

    @Test void marksBoundedUnicodeExcerptAsIncompleteRatherThanInventingSummaryFacts() {
        var entry = ConversationSynopsis.extract(3, 4, "😀".repeat(200), "不摘录我", "[]", "scope");
        assertThat(entry.text()).isEqualTo("😀".repeat(160));
        assertThat(entry.truncated()).isTrue();
    }
}
