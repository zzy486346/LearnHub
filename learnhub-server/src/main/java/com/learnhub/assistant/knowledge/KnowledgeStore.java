package com.learnhub.assistant.knowledge;

import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;

@Service
@Validated
public class KnowledgeStore {
    private static final int MAX_TOTAL_CONTENT_LENGTH = 100_000;
    private final KnowledgeJdbcRepository repository;
    private final KnowledgeChunker chunker;

    public KnowledgeStore(KnowledgeJdbcRepository repository) {
        this.repository = repository;
        this.chunker = new KnowledgeChunker();
    }

    @Transactional
    public ImportDocumentResult importDocument(@Valid ImportDocumentRequest request) {
        String sourceKey = request.sourceKey().trim();
        String title = request.title().trim();
        long totalContentLength = request.scenes().stream()
                .mapToLong(scene -> scene.content().codePointCount(0, scene.content().length()))
                .sum();
        if (totalContentLength > MAX_TOTAL_CONTENT_LENGTH) {
            throw new BusinessException("KNOWLEDGE_CONTENT_TOO_LARGE", "知识文档正文总长度不能超过100000个字符");
        }
        if (!repository.validLessonBinding(request.courseId(), request.lessonId())) {
            throw new BusinessException("KNOWLEDGE_LESSON_MISMATCH", "课程或课时不存在，或课时不属于指定课程");
        }

        List<KnowledgeChunker.DraftChunk> chunks = chunker.split(request.scenes());
        if (chunks.isEmpty()) throw new BusinessException("知识文档没有可导入的正文");
        String contentHash = contentHash(title, request.courseId(), request.lessonId(), request.scenes());

        repository.ensureDocument(sourceKey, title, request.courseId(), request.lessonId(), contentHash);
        KnowledgeJdbcRepository.LockedDocument document = repository.lockBySourceKey(sourceKey);
        if (document.deleted()) {
            throw new BusinessException("KNOWLEDGE_SOURCE_DELETED", "该来源标识已被删除，请使用新的来源标识");
        }
        if (contentHash.equals(document.contentHash())) {
            int existingCount = repository.chunkCount(document.id(), document.sourceVersion());
            if (existingCount > 0) {
                return result(document.id(), document.sourceVersion(), existingCount, true);
            }
            repository.insertChunks(document.id(), document.sourceVersion(), chunks);
            return result(document.id(), document.sourceVersion(), chunks.size(), false);
        }

        long nextVersion = document.sourceVersion() + 1;
        repository.updateVersion(document.id(), title, request.courseId(), request.lessonId(), contentHash, nextVersion);
        repository.insertChunks(document.id(), nextVersion, chunks);
        return result(document.id(), nextVersion, chunks.size(), false);
    }

    public List<KnowledgeDocumentRecord> pending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.pending(safeLimit);
    }

    public List<KnowledgeChunkRecord> chunks(long documentId, long version) {
        return repository.chunks(documentId, version);
    }

    public boolean markIndexed(long documentId, long version) {
        return repository.markIndexed(documentId, version);
    }

    public boolean markFailed(long documentId, long version) {
        return repository.markFailed(documentId, version);
    }

    public void requeueAll() {
        repository.requeueAll();
    }

    public Optional<CurrentKnowledgeChunk> currentChunk(long chunkId) {
        return repository.currentChunk(chunkId);
    }

    public List<CurrentKnowledgeChunk> currentChunks(List<Long> chunkIds) {
        if (chunkIds.size() > 100) throw new IllegalArgumentException("一次最多复核100个知识块");
        return repository.currentChunks(chunkIds);
    }

    private ImportDocumentResult result(long id, long version, int chunkCount, boolean unchanged) {
        return new ImportDocumentResult(Long.toString(id), version, chunkCount, unchanged);
    }

    private String contentHash(String title, long courseId, long lessonId,
                               List<ImportDocumentRequest.Scene> scenes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, KnowledgeChunker.STRATEGY_VERSION);
            update(digest, title);
            update(digest, Long.toString(courseId));
            update(digest, Long.toString(lessonId));
            for (ImportDocumentRequest.Scene scene : scenes) {
                update(digest, scene.heading() == null ? "" : scene.heading().trim());
                update(digest, scene.content().trim());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
