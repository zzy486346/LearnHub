package com.learnhub.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;

final class KnowledgeChunker {
    static final int MAX_CODE_POINTS = 600;
    private static final int PREFERRED_MIN_CODE_POINTS = 300;

    List<DraftChunk> split(List<ImportDocumentRequest.Scene> scenes) {
        List<DraftChunk> chunks = new ArrayList<>();
        int order = 0;
        for (ImportDocumentRequest.Scene scene : scenes) {
            String heading = trimToNull(scene.heading());
            int[] codePoints = scene.content().trim().codePoints().toArray();
            int start = 0;
            while (start < codePoints.length) {
                int end = Math.min(start + MAX_CODE_POINTS, codePoints.length);
                if (end < codePoints.length) {
                    int boundary = preferredBoundary(codePoints, start, end);
                    if (boundary > start) end = boundary;
                }
                String content = new String(codePoints, start, end - start).trim();
                if (!content.isEmpty()) chunks.add(new DraftChunk(order++, heading, content));
                start = end;
                while (start < codePoints.length && Character.isWhitespace(codePoints[start])) start++;
            }
        }
        return chunks;
    }

    private int preferredBoundary(int[] codePoints, int start, int end) {
        int minimum = Math.min(start + PREFERRED_MIN_CODE_POINTS, end);
        for (int index = end - 1; index >= minimum; index--) {
            int codePoint = codePoints[index];
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '。' || codePoint == '！'
                    || codePoint == '？' || codePoint == '.' || codePoint == '!' || codePoint == '?') {
                return index + 1;
            }
        }
        return end;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record DraftChunk(int order, String heading, String content) {
    }
}
