package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.learnhub.assistant.knowledge.KnowledgeStore;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VectorProjectionSyncTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final KnowledgeStore knowledge = mock(KnowledgeStore.class);
    private final VectorProjectionSync sync = new VectorProjectionSync(new JdbcTemplate(dataSource), knowledge);

    @Test
    void noAuthorizedLessonsCannotReachDatabaseOrModel() {
        assertThat(sync.currentVersions(1, List.of())).isEmpty();
        verifyNoInteractions(dataSource, knowledge);
    }

    @Test
    void rejectsMalformedServerScopeBeforeDatabaseAccess() {
        assertThatThrownBy(() -> sync.currentVersions(0, List.of(2L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sync.currentVersions(1, Arrays.asList(2L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sync.currentVersions(1, List.of(-1L))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dataSource, knowledge);
    }

    @Test
    void failureBackoffGrowsAndCapsAtOneHourWithoutOverflow() {
        assertThat(VectorProjectionSync.retrySeconds(0)).isEqualTo(30);
        assertThat(VectorProjectionSync.retrySeconds(1)).isEqualTo(60);
        assertThat(VectorProjectionSync.retrySeconds(6)).isEqualTo(1920);
        assertThat(VectorProjectionSync.retrySeconds(7)).isEqualTo(3600);
        assertThat(VectorProjectionSync.retrySeconds(Integer.MAX_VALUE)).isEqualTo(3600);
        assertThat(VectorProjectionSync.retrySeconds(-1)).isEqualTo(30);
    }
}
