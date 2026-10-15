package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void keepsSceneHeadingAndSplitsOnSentenceBoundaryWithoutOverlap() {
        String firstSentence = "甲".repeat(350) + "。";
        String secondSentence = "乙".repeat(350) + "。";

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene(" 场景一 ", firstSentence + secondSentence)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("场景一");
        assertThat(chunks.get(0).content()).isEqualTo(firstSentence);
        assertThat(chunks.get(1).content()).isEqualTo(secondSentence);
        assertThat(chunks).extracting(chunk -> chunk.content().codePointCount(0, chunk.content().length()))
                .allMatch(length -> length <= KnowledgeChunker.MAX_CODE_POINTS);
    }

    @Test
    void neverBreaksSupplementaryUnicodeCodePoint() {
        String content = "😀".repeat(601);

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(List.of(
                new ImportDocumentRequest.Scene(null, content)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content().codePointCount(0, chunks.get(0).content().length())).isEqualTo(600);
        assertThat(chunks.get(1).content()).isEqualTo("😀");
        assertThat(chunks.get(0).content() + chunks.get(1).content()).isEqualTo(content);
    }
}
