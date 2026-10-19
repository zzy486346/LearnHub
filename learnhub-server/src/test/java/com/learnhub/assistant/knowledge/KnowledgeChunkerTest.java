package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class KnowledgeChunkerTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void usesRecursiveSentenceBoundaryForLongText() {
        String firstSentence = "alpha ".repeat(58) + "ends.";
        String secondSentence = "beta ".repeat(70) + "ends.";

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene(" 场景一 ", firstSentence + " " + secondSentence)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("场景一");
        assertThat(chunks.get(0).content()).isEqualTo(firstSentence);
        assertThat(chunks.get(1).content()).isEqualTo(secondSentence);
        assertThat(chunks).extracting(chunk -> chunk.content().length())
                .allMatch(length -> length <= KnowledgeChunker.MAX_UTF16_UNITS);
    }

    @Test
    void keepsChineseLongTextBoundedAndComplete() {
        String content = java.util.stream.IntStream.range(0, 900)
                .mapToObj(index -> Character.toString(0x4E00 + index))
                .reduce("", String::concat);

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene("中文长段", content)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(chunk -> chunk.content().length())
                .allMatch(length -> length <= KnowledgeChunker.MAX_UTF16_UNITS);
        assertThat(covers(content, chunks)).isTrue();
    }

    @Test
    void preservesSceneHeadingAndGlobalOrder() {
        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene(" 第一节 ", "短内容"),
                new ImportDocumentRequest.Scene("第二节", "乙".repeat(700))));

        assertThat(chunks).extracting(KnowledgeChunker.DraftChunk::order)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.get(0).heading()).isEqualTo("第一节");
        assertThat(chunks.subList(1, chunks.size())).extracting(KnowledgeChunker.DraftChunk::heading)
                .containsOnly("第二节");
    }

    @Test
    void unicodeFallbackNeverBreaksSupplementaryCodePointAndBoundsOverlap() {
        String content = "😀".repeat(601);

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene(null, content)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).hasSizeLessThanOrEqualTo(KnowledgeChunker.MAX_UTF16_UNITS);
            assertThat(hasUnpairedSurrogate(chunk.content())).isFalse();
        });
        assertThat(covers(content, chunks)).isTrue();
    }

    @Test
    void strategyVersionRecordsSplitterParametersAndFallback() {
        assertThat(KnowledgeChunker.STRATEGY_VERSION)
                .contains("langchain4j-recursive", "utf16", "max=600", "overlap=60", "codepoint-v1");
    }

    @Test
    @Timeout(2)
    void repeatedChineseAtBusinessLimitHasBoundedCoverageValidation() {
        List<ImportDocumentRequest.Scene> scenes = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new ImportDocumentRequest.Scene("场景" + index, "重".repeat(20_000)))
                .toList();

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(scenes);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).extracting(chunk -> chunk.content().length())
                .allMatch(length -> length <= KnowledgeChunker.MAX_UTF16_UNITS);
        assertThat(chunks).extracting(KnowledgeChunker.DraftChunk::order)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void skipsWhitespaceOnlySceneBeforeCallingFrameworkSplitter() {
        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene("空白", " \r\n\t "),
                new ImportDocumentRequest.Scene("正文", "有效内容")));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.order()).isZero();
            assertThat(chunk.heading()).isEqualTo("正文");
            assertThat(chunk.content()).isEqualTo("有效内容");
        });
    }

    @Test
    void malformedUnicodeIsRejectedRatherThanPersistedAsReplacementCharacters() {
        assertThatThrownBy(() -> chunker.split(List.of(new ImportDocumentRequest.Scene("标题", "正文\uD800"))))
                .isInstanceOf(com.learnhub.common.exception.BusinessException.class)
                .hasMessageContaining("Unicode");
        assertThatThrownBy(() -> chunker.split(List.of(new ImportDocumentRequest.Scene("\uDC00", "正文"))))
                .isInstanceOf(com.learnhub.common.exception.BusinessException.class);
    }

    private boolean covers(String content, List<KnowledgeChunker.DraftChunk> chunks) {
        if (!content.startsWith(chunks.get(0).content())) return false;
        return covers(content, chunks, 1, chunks.get(0).content().length());
    }

    private boolean covers(String content, List<KnowledgeChunker.DraftChunk> chunks, int index, int coveredEnd) {
        if (index == chunks.size()) return coveredEnd == content.length();
        String next = chunks.get(index).content();
        int minimumStart = Math.max(0, coveredEnd - KnowledgeChunker.MAX_OVERLAP_UTF16_UNITS);
        for (int start = minimumStart; start <= coveredEnd; start++) {
            int nextEnd = start + next.length();
            if (nextEnd > coveredEnd && nextEnd <= content.length() && content.startsWith(next, start)
                    && covers(content, chunks, index + 1, nextEnd)) return true;
        }
        return false;
    }

    private boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
