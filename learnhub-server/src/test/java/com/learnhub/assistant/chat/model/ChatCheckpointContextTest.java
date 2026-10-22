package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.qa.*;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChatCheckpointContextTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    final ChatHistoryService history = mock(ChatHistoryService.class);
    final CourseQaService qa = mock(CourseQaService.class);
    final AssistantLimits limits = new AssistantLimits();
    final List<ContextCheckpoint.Event> rows = new ArrayList<>();

    ChatCheckpointContextTest() {
        when(persistence.contextEvents(eq(7L), eq("c"), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(2); int count = call.getArgument(3);
            return rows.stream().filter(event -> event.message().seq() > after).limit(count).toList();
        });
        when(history.verify(eq(7L), eq(1L), any())).thenAnswer(call -> {
            var row = (ChatPersistenceService.Message) call.getArgument(2);
            return new ChatHistoryService.HistoryMessage(row.id(), row.seq(), row.role(), row.content(),
                    row.status(), row.runId(), List.of(), row.content().equals("restricted"));
        });
        when(qa.evidenceBook(7L, 1L)).thenReturn(mock(EvidenceBook.class));
    }

    @Test void scansPast48AndRetainsPairAcrossPageWhileOrphansNeverBecomeSummaryMessages() {
        rows.add(orphan(1));
        for (int index = 0; index < 26; index++) pair(2 + index * 2L, "safe");
        var result = recover(() -> { });
        assertThat(result.messages()).hasSize(52);
        assertThat(result.events()).hasSize(53);
        assertThat(result.messages()).extracting(ChatPersistenceService.Message::seq).doesNotContain(1L).contains(48L, 49L, 53L);
        assertThat(result.scanLimited()).isFalse();
        verify(persistence).contextEvents(7, "c", 48, 48);
    }

    @Test void restrictedGroupResetsCoverageFloorAndDiscardsEarlierReadablePrefix() {
        pair(1, "restricted"); pair(3, "safe");
        var result = recover(() -> { });
        assertThat(result.floorSeq()).isEqualTo(2);
        assertThat(result.barrier().message().seq()).isEqualTo(2);
        assertThat(result.messages()).extracting(ChatPersistenceService.Message::seq).containsExactly(3L, 4L);
        assertThat(result.events()).extracting(event -> event.message().seq()).containsExactly(3L, 4L);
        assertThat(result.summary()).isNull();
    }

    @Test void unreadOrMalformedGapCannotBeClaimedAsCovered() {
        pair(1, "safe"); pair(5, "safe");
        var result = recover(() -> { });
        assertThat(result.scanLimited()).isTrue();
        assertThat(result.messages()).extracting(ChatPersistenceService.Message::seq).containsExactly(1L, 2L);
    }

    @Test void boundedReadDoesNotMovePastUnscannedPrefix() {
        pair(1, "safe"); pair(3, "safe"); pair(5, "safe");
        limits.setContextRecoveryMaxMessages(4);
        var result = recover(() -> { });
        assertThat(result.scanLimited()).isTrue();
        assertThat(result.events()).hasSize(4);
    }

    @Test void currentRunningUserStopsScanAndCannotEnterCheckpointProof() {
        pair(1, "safe");
        var row = new ChatPersistenceService.Message("running-user", 3, "USER", "current", "COMPLETED", "current", null);
        rows.add(new ContextCheckpoint.Event(row, "RUNNING", row.id(), null));
        assertThat(recover(() -> { }).events()).hasSize(2);
    }

    @Test void cancellationIsNeverConvertedToCheckpointInvalidation() {
        assertThatThrownBy(() -> recover(() -> { throw new BusinessException("ASSISTANT_CANCELLED", "cancel"); }))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_CANCELLED"));
    }

    @Test void provenanceRejectsDuplicateIdentitiesAndUnknownFields() {
        pair(1, "safe");
        var proof = ChatCheckpointContext.proof(rows.get(0).message());
        assertThatThrownBy(() -> ChatCheckpointContext.encodeProvenance(mapper, List.of(proof, proof), null))
                .isInstanceOf(BusinessException.class);
        assertThat(ChatCheckpointContext.encodeProvenance(mapper, List.of(proof), null)).contains("contentHash", "\"barrier\":null");
    }

    @Test void incompatibleOrForgedCheckpointNeverRestoresItsSummary() throws Exception {
        pair(1, "safe");
        String summary = summary(1);
        var actualProof = ChatCheckpointContext.proof(rows.get(0).message());
        var forged = new ChatCheckpointContext.SourceProof(actualProof.seq(), actualProof.messageId(), actualProof.runId(),
                actualProof.role(), "f".repeat(64), actualProof.dependencyKeys());
        String proof = ChatCheckpointContext.encodeProvenance(mapper, List.of(forged), null);
        when(persistence.run(7, "publisher")).thenReturn(new ChatPersistenceService.Run("publisher", "c", 7, "request", "COMPLETED", null, "q"));
        for (String prompt : List.of("wrong-prompt", "p")) {
            when(persistence.contextCheckpoint(7, "c", 1)).thenReturn(new ContextCheckpoint.Snapshot(1, 0, 1, 2, "a".repeat(64),
                    summary, "[]", proof, 1, prompt, "m", "publisher", "NONE"));
            var result = recover(() -> { });
            assertThat(result.expected()).isNotNull();
            assertThat(result.valid()).isNull();
            assertThat(result.summary()).isNull();
            assertThat(result.messages()).hasSize(2);
        }
    }

    @Test void temporaryRestrictedPrefixIsRebuiltFromStartWhenBarrierBecomesReadable() throws Exception {
        pair(1, "safe"); pair(3, "safe");
        var barrier = rows.get(1);
        String provenance = ChatCheckpointContext.encodeProvenance(mapper,
                List.of(ChatCheckpointContext.proof(rows.get(2).message())), barrier, rows.get(3));
        when(persistence.contextCheckpoint(7, "c", 1)).thenReturn(new ContextCheckpoint.Snapshot(1, 2, 3, 4, "a".repeat(64),
                summary(3), "[]", provenance, 1, "p", "m", "publisher", "RESTRICTED_PREFIX"));
        when(persistence.run(7, "publisher")).thenReturn(new ChatPersistenceService.Run("publisher", "c", 7, "request", "COMPLETED", null, "q"));
        var result = recover(() -> { });
        assertThat(result.valid()).isNull();
        assertThat(result.floorSeq()).isZero();
        assertThat(result.barrier()).isNull();
        assertThat(result.messages()).extracting(ChatPersistenceService.Message::seq).containsExactly(1L, 2L, 3L, 4L);
    }

    private String summary(long seq) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        for (String name : List.of("goal", "topic", "constraints", "decisions", "corrections", "openQuestions", "nextStep")) task.put(name, List.of());
        task.put("goal", List.of(Map.of("value", "CHECKPOINT_SENTINEL", "sourceMessageSeqs", List.of(seq), "dependencyKeys", List.of(), "inferred", false)));
        return mapper.writeValueAsString(Map.of("schemaVersion", 1, "taskState", task, "conversationSummary", List.of(), "unresolvedReferences", List.of()));
    }

    private ChatCheckpointContext.Recovered recover(Runnable check) {
        return new ChatCheckpointContext(persistence, history, qa, null, mapper, limits)
                .recover(7, 1, "c", "current", "p", "m", check);
    }

    private void pair(long first, String answer) {
        String run = "run-" + first;
        var user = new ChatPersistenceService.Message("m" + first, first, "USER", "q", "COMPLETED", run, null);
        var assistant = new ChatPersistenceService.Message("m" + (first + 1), first + 1, "ASSISTANT", answer, "COMPLETED", run, "[]");
        rows.add(new ContextCheckpoint.Event(user, "COMPLETED", user.id(), assistant.id()));
        rows.add(new ContextCheckpoint.Event(assistant, "COMPLETED", user.id(), assistant.id()));
    }

    private ContextCheckpoint.Event orphan(long seq) {
        var user = new ChatPersistenceService.Message("o" + seq, seq, "USER", "cancelled", "COMPLETED", "orphan", null);
        return new ContextCheckpoint.Event(user, "CANCELLED", user.id(), null);
    }
}
