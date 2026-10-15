package com.learnhub.assistant.knowledge;

public record CurrentKnowledgeChunk(long id, long documentId, long sourceVersion, long courseId, long lessonId,
                                    String title, int chunkOrder, String heading, String content) {
}
