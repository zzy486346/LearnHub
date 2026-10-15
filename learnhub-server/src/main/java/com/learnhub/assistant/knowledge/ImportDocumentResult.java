package com.learnhub.assistant.knowledge;

public record ImportDocumentResult(String documentId, long sourceVersion, int chunkCount, boolean unchanged) {
}
