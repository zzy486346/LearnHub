package com.learnhub.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseIndexSyncServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void missingCourseDeletesDocumentAndCompletesOnlySelectedGeneration() throws Exception {
        CourseIndexTaskMapper tasks = mock(CourseIndexTaskMapper.class);
        CourseIndexSourceMapper sources = mock(CourseIndexSourceMapper.class);
        CourseSearchGateway gateway = mock(CourseSearchGateway.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(gateway.aliasName()).thenReturn("learnhub-courses-search");
        when(gateway.ensureAlias()).thenReturn(true);
        when(connection.prepareStatement(any(String.class))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                invocation.<ConnectionCallback<Integer>>getArgument(0).doInConnection(connection));
        CourseIndexTaskEntity task = new CourseIndexTaskEntity();
        task.setCourseId(31L);
        task.setGeneration(7L);
        task.setAttempts(0);
        task.setNextRetryAt(LocalDateTime.now());
        when(tasks.selectDue(10)).thenReturn(List.of(task));
        when(tasks.deleteIfGeneration(31L, 7L)).thenReturn(1);
        when(sources.selectByCourseId(31L)).thenReturn(null);

        int completed = new CourseIndexSyncService(tasks, sources, gateway, jdbc,
                new ObjectMapper(), 100).syncDue(10);

        assertThat(completed).isEqualTo(1);
        verify(gateway).delete(31L);
        verify(tasks).deleteIfGeneration(31L, 7L);

        when(result.getInt(1)).thenReturn(0);
        var service = new CourseIndexSyncService(tasks, sources, gateway, jdbc, new ObjectMapper(), 100);
        org.junit.jupiter.api.Assertions.assertEquals("SEARCH_SYNC_BUSY",
                org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, service::adminFullRebuild).getCode());
    }
}
