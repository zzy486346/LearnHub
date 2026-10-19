package com.learnhub.assistant.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import com.learnhub.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;

final class KnowledgeChunker {
    static final int MAX_UTF16_UNITS = 600;
    static final int MAX_OVERLAP_UTF16_UNITS = 60;
    static final String STRATEGY_VERSION =
            "langchain4j-recursive:utf16:max=600:overlap=60:unicode-fallback=codepoint-v1";

    private final DocumentSplitter splitter =
            DocumentSplitters.recursive(MAX_UTF16_UNITS, MAX_OVERLAP_UTF16_UNITS);

    List<DraftChunk> split(List<ImportDocumentRequest.Scene> scenes) {
        List<DraftChunk> chunks = new ArrayList<>();
        int order = 0;
        for (ImportDocumentRequest.Scene scene : scenes) {
            String heading = trimToNull(scene.heading());
            String content = scene.content().trim();
            if (containsUnpairedSurrogate(content) || (heading != null && containsUnpairedSurrogate(heading)))
                throw new BusinessException("KNOWLEDGE_INVALID_UNICODE", "讲解稿包含无效的 Unicode 字符");
            if (content.isEmpty()) continue;
            List<String> sceneChunks = frameworkSplit(content);
            if (containsUnpairedSurrogate(sceneChunks) || !losslesslyCovers(content, sceneChunks)) {
                sceneChunks = unicodeSafeFallback(content);
            }
            for (String chunk : sceneChunks) {
                if (!chunk.isBlank()) chunks.add(new DraftChunk(order++, heading, chunk));
            }
        }
        return chunks;
    }

    private List<String> frameworkSplit(String content) {
        return splitter.split(Document.from(content)).stream()
                .map(TextSegment::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    private boolean containsUnpairedSurrogate(List<String> chunks) {
        return chunks.stream().anyMatch(this::containsUnpairedSurrogate);
    }

    private boolean containsUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) return true;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean losslesslyCovers(String original, List<String> chunks) {
        if (chunks.isEmpty()) return original.isEmpty();
        if (chunks.stream().anyMatch(chunk -> chunk.length() > MAX_UTF16_UNITS)) return false;
        String normalizedOriginal = withoutWhitespace(original);
        List<String> normalizedChunks = chunks.stream().map(this::withoutWhitespace).toList();
        String first = normalizedChunks.get(0);
        if (!normalizedOriginal.startsWith(first)) return false;
        int coveredEnd = first.length();
        for (int chunkIndex = 1; chunkIndex < normalizedChunks.size(); chunkIndex++) {
            String chunk = normalizedChunks.get(chunkIndex);
            int minimumStart = Math.max(0, coveredEnd - MAX_OVERLAP_UTF16_UNITS);
            boolean matched = false;
            // Prefer the position with the greatest forward progress. A repeated text can make an
            // earlier overlapping position ambiguous; if this bounded proof fails, the safe fallback
            // handles the whole scene instead of recursively exploring up to 61^N combinations.
            for (int start = coveredEnd; start >= minimumStart; start--) {
                int nextEnd = start + chunk.length();
                if (nextEnd <= coveredEnd || nextEnd > normalizedOriginal.length()) continue;
                if (normalizedOriginal.startsWith(chunk, start)) {
                    coveredEnd = nextEnd;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return coveredEnd == normalizedOriginal.length();
    }

    private String withoutWhitespace(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * LangChain4j's character-count overload counts UTF-16 units. If its recursive character fallback
     * ever cuts a surrogate pair, the whole scene is split here at code-point boundaries. The fallback
     * retains the same 600-unit maximum and no more than 60 UTF-16 units of overlap.
     */
    private List<String> unicodeSafeFallback(String content) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + MAX_UTF16_UNITS, content.length());
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))
                    && Character.isLowSurrogate(content.charAt(end))) {
                end--;
            }
            chunks.add(content.substring(start, end));
            if (end == content.length()) break;
            int nextStart = end - Math.min(MAX_OVERLAP_UTF16_UNITS, end - start);
            if (nextStart < end && Character.isLowSurrogate(content.charAt(nextStart))) nextStart++;
            if (nextStart <= start) nextStart = end;
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record DraftChunk(int order, String heading, String content) {
    }
}
