package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.qa.GroundedAnswer;
import com.learnhub.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GroundedAnswerStreamTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceBook book = mock(EvidenceBook.class);
    private final Runnable checkpoint = mock(Runnable.class);
    private final List<String> deltas = new ArrayList<>();

    @BeforeEach void evidence() {
        when(book.contains("E1")).thenReturn(true);
        when(book.contains("E2")).thenReturn(true);
        when(book.contains("E3")).thenReturn(true);
        when(book.kind("E1")).thenReturn("MATERIAL");
        when(book.kind("E2")).thenReturn("PROGRESS");
        when(book.kind("E3")).thenReturn("OUTLINE");
    }

    @Test void firstCompleteSegmentIsVisibleBeforeTheJsonDocumentEnds() {
        try (var stream = stream()) {
            stream.accept("{\"answerSegments\":[{\"text\":\"第一段\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"},");
            assertThat(deltas).containsExactly("第一段 [E1]");
            stream.accept("{\"kind\":\"FACT\",\"evidenceIds\":[\"E1\"],\"text\":\"第二段\"}],\"insufficientEvidence\":false}");
            assertThat(deltas).containsExactly("第一段 [E1]", "\n\n第二段 [E1]");
            assertThat(stream.complete(verified(false, fact("第一段"), fact("第二段"))))
                    .isEqualTo("第一段 [E1]\n\n第二段 [E1]");
            verify(book, times(3)).check();
        }
    }

    @Test void everyCharacterMayBeSplitAcrossJsonEscapesAndEmojiSurrogates() throws Exception {
        String body = "中文😀、引号\"、反斜杠\\、换行\n结束";
        String raw = mapper.writeValueAsString(java.util.Map.of("insufficientEvidence", false,
                "answerSegments", List.of(fact(body))));
        // 同时覆盖转义 Unicode 的六字符序列，而非仅覆盖原生 UTF-8。
        raw = raw.replace("中文", "\\u4e2d\\u6587");
        try (var stream = stream()) {
            for (int i = 0; i < raw.length(); i++) stream.accept(raw.substring(i, i + 1));
            assertThat(stream.complete(verified(false, fact(body)))).isEqualTo(body + " [E1]");
            assertThat(deltas).containsExactly(body + " [E1]");
        }
    }

    @Test void suggestionNeedsProgressAndOutlineInTheSameSegmentBeforeDisplayingIt() {
        try (var stream = stream()) {
            stream.accept("{\"insufficientEvidence\":false,\"answerSegments\":[");
            assertThatThrownBy(() -> stream.accept("{\"text\":\"建议学下一节\",\"kind\":\"SUGGESTION\",\"evidenceIds\":[\"E1\"]}"))
                    .isInstanceOf(BusinessException.class);
            assertThat(deltas).isEmpty();
        }
    }

    @Test void validSuggestionEmitsOnlyAfterItsCompleteObject() {
        var segment = new GroundedAnswer.Segment("根据进度学习下一节", List.of("E2", "E3"), "SUGGESTION");
        try (var stream = stream()) {
            stream.accept("{\"answerSegments\":[{\"text\":\"根据进度学习下一节\",\"evidenceIds\":[\"E2\",\"E3\"],\"kind\":\"SUGGESTION\"");
            assertThat(deltas).isEmpty();
            stream.accept("}],\"insufficientEvidence\":false}");
            assertThat(stream.complete(verified(false, segment))).isEqualTo("根据进度学习下一节 [E2][E3]");
        }
    }

    @Test void finalVerifiedAnswerMustExactlyMatchEmittedSegments() {
        try (var stream = stream()) {
            stream.accept("{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"正文\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}");
            assertThatThrownBy(() -> stream.complete(verified(false, fact("不同正文"))))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> stream.accept(" ")).isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"伪造 [E999]", "重复 [E1]", "混淆 [e2]"})
    void inlineCitationMarkersCannotBeDisplayedBeforeFinalVerification(String body) {
        try (var stream = stream()) {
            String json = "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"" + body
                    + "\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}";
            assertThatThrownBy(() -> stream.accept(json)).isInstanceOf(BusinessException.class);
            assertThat(deltas).isEmpty();
        }
    }

    @Test void insufficientEvidenceEmitsOnlyFixedTextAfterFinalVerification() {
        try (var stream = stream()) {
            stream.accept("{\"answerSegments\":[],\"insufficientEvidence\":true}");
            assertThat(deltas).isEmpty();
            assertThat(stream.complete(verified(true))).isEqualTo("资料不足");
            assertThat(deltas).containsExactly("资料不足");
        }
    }

    @Test void revocationBeforeCompletedSegmentSuppressesIt() {
        doThrow(new BusinessException("ASSISTANT_EVIDENCE_CHANGED", "权限变化")).when(book).check();
        try (var stream = stream()) {
            assertThatThrownBy(() -> stream.accept("{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"受控正文\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("权限变化");
            assertThat(deltas).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "[]", "{\"extra\":true}", "{\"insufficientEvidence\":\"false\"}",
            "{\"answerSegments\":[[]]}",
            "{\"answerSegments\":[{\"text\":\"正文\",\"text\":\"重复\"}]}",
            "{\"answerSegments\":[{\"text\":\"正文\",\"kind\":\"FACT\",\"evidenceIds\":[\"E99\"]}]}",
            "{\"answerSegments\":[{\"text\":\"https://evil.invalid\",\"kind\":\"FACT\",\"evidenceIds\":[\"E1\"]}]}",
            "{\"answerSegments\":[{\"text\":\"\\uD800\",\"kind\":\"FACT\",\"evidenceIds\":[\"E1\"]}]}",
            "{\"answerSegments\":[{\"text\":{},\"kind\":\"FACT\",\"evidenceIds\":[\"E1\"]}]}"
    }) void malformedOrUnsafeSegmentFailsClosedWithoutDisplayingIt(String raw) {
        try (var stream = stream()) {
            assertThatThrownBy(() -> stream.accept(raw)).isInstanceOf(BusinessException.class);
            assertThat(deltas).isEmpty();
            assertThatThrownBy(() -> stream.accept("{}")).isInstanceOf(BusinessException.class);
        }
    }

    @Test void truncatedJsonAndUnpairedRawSurrogateCannotComplete() {
        try (var stream = stream()) {
            stream.accept("{\"answerSegments\":[");
            assertThatThrownBy(() -> stream.complete(verified(true))).isInstanceOf(BusinessException.class);
        }
        try (var stream = stream()) {
            stream.accept("{\"answerSegments\":[{\"text\":\"" + '\uD83D');
            assertThatThrownBy(() -> stream.complete(verified(false))).isInstanceOf(BusinessException.class);
            assertThat(deltas).isEmpty();
        }
    }

    private GroundedAnswerStream stream() {
        return new GroundedAnswerStream(mapper, book, checkpoint, deltas::add, "资料不足");
    }
    private static GroundedAnswer.Segment fact(String text) {
        return new GroundedAnswer.Segment(text, List.of("E1"), "FACT");
    }
    private static GroundedAnswer verified(boolean insufficient, GroundedAnswer.Segment... segments) {
        return new GroundedAnswer("run", List.of(segments), List.of(), insufficient,
                "MOCK", "TOOLS", "test", List.of(), null);
    }
}
