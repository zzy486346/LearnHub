package com.learnhub.assistant.qa;

import com.learnhub.assistant.runtime.AssistantRunSummary;
import java.util.List;

public record GroundedAnswer(String requestId, List<Segment> answerSegments, List<EvidenceBook.Citation> citations,
        boolean insufficientEvidence, String modelMode, String retrievalMode, String promptVersion,
        List<String> trace, AssistantRunSummary usage) {
    public record Segment(String text, List<String> evidenceIds, String kind) { }
}
