package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeStoreTest {
    private final KnowledgeJdbcRepository repository = mock(KnowledgeJdbcRepository.class);
    private final KnowledgeStore store = new KnowledgeStore(repository);

    @Test
    void rejectsLessonThatDoesNotBelongToCourse() {
        ImportDocumentRequest request = request("正文");
        when(repository.validLessonBinding(11L, 22L)).thenReturn(false);

        assertThatThrownBy(() -> store.importDocument(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("KNOWLEDGE_LESSON_MISMATCH"));

        verify(repository, never()).ensureDocument(anyString(), anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void repeatedContentReusesCurrentVersionAndChunks() {
        ImportDocumentRequest request = request("正文");
        when(repository.validLessonBinding(11L, 22L)).thenReturn(true);
        when(repository.lockBySourceKey("course-11-lesson-22"))
                .thenAnswer(invocation -> new KnowledgeJdbcRepository.LockedDocument(
                        9007199254740993L, "文档", 11L, 22L, capturedHash(request), 3L, false));
        when(repository.chunkCount(9007199254740993L, 3L)).thenReturn(2);

        ImportDocumentResult result = store.importDocument(request);

        assertThat(result.documentId()).isEqualTo("9007199254740993");
        assertThat(result.sourceVersion()).isEqualTo(3L);
        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.unchanged()).isTrue();
        verify(repository, never()).updateVersion(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong());
        verify(repository, never()).insertChunks(anyLong(), anyLong(), anyList());
    }

    @Test
    void changedContentCreatesNextServerVersion() {
        ImportDocumentRequest request = request("新正文");
        when(repository.validLessonBinding(11L, 22L)).thenReturn(true);
        when(repository.lockBySourceKey("course-11-lesson-22"))
                .thenReturn(new KnowledgeJdbcRepository.LockedDocument(91L, "旧文档", 11L, 22L,
                        "old-hash", 7L, false));

        ImportDocumentResult result = store.importDocument(request);

        assertThat(result.sourceVersion()).isEqualTo(8L);
        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(result.unchanged()).isFalse();
        verify(repository).updateVersion(eq(91L), eq("文档"), eq(11L), eq(22L), anyString(), eq(8L));
        verify(repository).insertChunks(eq(91L), eq(8L), anyList());
    }

    @Test
    void rejectsDeletedSourceKeyWithBusinessError() {
        ImportDocumentRequest request = request("正文");
        when(repository.validLessonBinding(11L, 22L)).thenReturn(true);
        when(repository.lockBySourceKey("course-11-lesson-22"))
                .thenReturn(new KnowledgeJdbcRepository.LockedDocument(91L, "文档", 11L, 22L,
                        "old-hash", 2L, true));

        assertThatThrownBy(() -> store.importDocument(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("KNOWLEDGE_SOURCE_DELETED"));
    }

    @Test
    void rejectsOversizedCombinedScenesBeforeDatabaseAccess() {
        ImportDocumentRequest request = new ImportDocumentRequest("large", "文档", 11L, 22L,
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(index -> new ImportDocumentRequest.Scene("场景" + index, "字".repeat(17_000)))
                        .toList());

        assertThatThrownBy(() -> store.importDocument(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("KNOWLEDGE_CONTENT_TOO_LARGE"));
        verify(repository, never()).validLessonBinding(anyLong(), anyLong());
    }

    @Test
    void requeueAllDelegatesToFactStoreRepository() {
        store.requeueAll();

        verify(repository).requeueAll();
    }

    private ImportDocumentRequest request(String content) {
        return new ImportDocumentRequest("course-11-lesson-22", "文档", 11L, 22L,
                List.of(new ImportDocumentRequest.Scene("场景", content)));
    }

    private String capturedHash(ImportDocumentRequest request) {
        KnowledgeJdbcRepository capture = mock(KnowledgeJdbcRepository.class);
        KnowledgeStore hashingStore = new KnowledgeStore(capture);
        when(capture.validLessonBinding(11L, 22L)).thenReturn(true);
        when(capture.lockBySourceKey("course-11-lesson-22"))
                .thenAnswer(invocation -> {
                    throw new HashCaptured();
                });
        try {
            hashingStore.importDocument(request);
        } catch (HashCaptured ignored) {
            // The hash is captured from the preceding ensureDocument invocation below.
        }
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(capture).ensureDocument(eq("course-11-lesson-22"), eq("文档"), eq(11L), eq(22L), captor.capture());
        return captor.getValue();
    }

    private static final class HashCaptured extends RuntimeException {
    }
}
