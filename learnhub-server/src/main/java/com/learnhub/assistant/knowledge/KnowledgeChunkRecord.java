package com.learnhub.assistant.knowledge;

public record KnowledgeChunkRecord(long id, long documentId, long sourceVersion, int chunkOrder,
                                   String heading, String content) {
}
