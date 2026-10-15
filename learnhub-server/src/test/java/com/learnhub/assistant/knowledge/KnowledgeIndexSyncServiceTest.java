package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeIndexSyncServiceTest {
    final KnowledgeStore store = mock(KnowledgeStore.class);
    final KnowledgeSearchGateway gateway = mock(KnowledgeSearchGateway.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement statement = mock(PreparedStatement.class);
    final ResultSet result = mock(ResultSet.class);
    final KnowledgeIndexSyncService service = new KnowledgeIndexSyncService(store, gateway, jdbc);
    final KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(1, 2, 3, 4, "标题");
    final KnowledgeChunkRecord chunk = new KnowledgeChunkRecord(5, 1, 2, 0, null, "正文");

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        when(gateway.aliasName()).thenReturn("learnhub-knowledge-search");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(call ->
                ((ConnectionCallback<Integer>) call.getArgument(0)).doInConnection(connection));
        when(store.pending(20)).thenReturn(List.of(document));
        when(store.chunks(1, 2)).thenReturn(List.of(chunk));
    }

    @Test void refreshBeforeVersionGuardedCompletionAndAlwaysReleaseLock() throws Exception {
        when(store.markIndexed(1, 2)).thenReturn(true);
        assertThat(service.syncDue(20)).isEqualTo(1);
        var ordered = inOrder(gateway, store);
        ordered.verify(gateway).index(document, chunk);
        ordered.verify(gateway).removeOtherVersions(1, 2);
        ordered.verify(gateway).refresh();
        ordered.verify(store).markIndexed(1, 2);
        verify(connection).prepareStatement("SELECT RELEASE_LOCK(?)");
    }

    @Test void failedWriteRemainsRetryableAndIsNeverMarkedIndexed() {
        doThrow(new IllegalStateException("模拟ES故障")).when(gateway).index(document, chunk);
        assertThat(service.syncDue(20)).isZero();
        verify(store).markFailed(1, 2);
        verify(store, never()).markIndexed(anyLong(), anyLong());
    }

    @Test void oldVersionCompletionDoesNotCountAsSuccessful() {
        when(store.markIndexed(1, 2)).thenReturn(false);
        assertThat(service.syncDue(20)).isZero();
        verify(store, never()).markFailed(anyLong(), anyLong());
    }

    @Test void missingAliasRequeuesFactsAndBusyLockSkipsWork() throws Exception {
        when(gateway.ensureAlias()).thenReturn(true);
        service.syncDue(20);
        verify(store).requeueAll();
        clearInvocations(store, gateway);
        when(result.getInt(1)).thenReturn(0);
        assertThat(service.syncDue(20)).isZero();
        verifyNoInteractions(store);
        verify(gateway, never()).ensureAlias();
    }
}
