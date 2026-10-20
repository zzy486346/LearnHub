package com.learnhub.assistant.chat.model;

import java.util.List;
import java.util.Objects;

/**
 * Deterministically chooses the text sent to retrieval. It does not resolve
 * entities, change course scope, or claim to understand a complete reference.
 */
public final class ConversationQueryPlanner {
    private static final int MAX_CODE_POINTS = 500;
    private static final String REFERENCES = "(它|这个|那个|刚才|上述|继续|再讲|接着)";

    private ConversationQueryPlanner() { }

    public static Plan plan(String question, List<String> reviewedUserQuestions) {
        String current = requireQuestion(question);
        List<String> history = reviewedUserQuestions == null ? List.of() : reviewedUserQuestions;
        if (!isReferential(current)) return new Plan(limit(current), Mode.INDEPENDENT);

        String subject = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String candidate = history.get(i);
            if (candidate == null || candidate.isBlank() || isReferential(candidate)) continue;
            subject = candidate;
            break;
        }
        if (subject == null) return new Plan(limit(current), Mode.REFERENTIAL);
        return new Plan(limit(current + "\n" + subject), Mode.REFERENTIAL);
    }

    private static String requireQuestion(String value) {
        if (value == null || value.isBlank() || !wellFormedUnicode(value))
            throw new IllegalArgumentException("question must be non-blank and well-formed Unicode");
        return value.strip();
    }

    private static boolean isReferential(String value) {
        return value.matches("(?s).*" + REFERENCES + ".*");
    }

    private static String limit(String value) {
        int count = value.codePointCount(0, value.length());
        return count <= MAX_CODE_POINTS ? value : value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS));
    }

    private static boolean wellFormedUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    public enum Mode { INDEPENDENT, REFERENTIAL }

    public record Plan(String query, Mode mode) {
        public Plan {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(mode, "mode");
        }
    }
}
