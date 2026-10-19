package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learnhub.assistant.knowledge.KnowledgeSearchGateway.Hit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    @Test
    void fusesChannelRanksInsteadOfRawScores() {
        List<Hit> result = ReciprocalRankFusion.fuse(
                List.of(hit(1, 99999), hit(2, 1)), List.of(hit(2, -9), hit(3, 999)), 100);

        assertThat(result).extracting(Hit::chunkId).containsExactly(2L, 1L, 3L);
        assertThat(result.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(result.get(1).score()).isEqualTo(1.0 / 61);
    }

    @Test
    void deduplicatesWithinEachChannelBeforeAssigningRanks() {
        List<Hit> result = ReciprocalRankFusion.fuse(
                List.of(hit(1, 1), hit(1, 9), hit(2, 1)),
                List.of(hit(2, 1), hit(2, 9)), 100);

        assertThat(result).extracting(Hit::chunkId).containsExactly(2L, 1L);
        assertThat(result.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(result.get(1).score()).isEqualTo(1.0 / 61);
    }

    @Test
    void discardsDocumentAndVersionConflictsAcrossOrWithinChannels() {
        List<Hit> result = ReciprocalRankFusion.fuse(
                List.of(hit(1, 1), new Hit(1, 999, 1, 1), hit(2, 1), hit(3, 1)),
                List.of(new Hit(2, 2, 2, 1), hit(1, 1), hit(4, 1)), 100);

        assertThat(result).extracting(Hit::chunkId).containsExactly(3L, 4L);
        assertThat(result).extracting(Hit::score).containsOnly(1.0 / 61);
    }

    @Test
    void keepsFirstAppearanceOrderForTies() {
        List<Hit> result = ReciprocalRankFusion.fuse(
                List.of(hit(5, 1), hit(7, 1)), List.of(hit(7, 1), hit(5, 1)), 100);

        assertThat(result).extracting(Hit::chunkId).containsExactly(5L, 7L);
        assertThat(result.get(0).score()).isEqualTo(result.get(1).score());
    }

    @Test
    void truncatesSortedResultsToRequestedLimitAndHardMaximum() {
        List<Hit> candidates = LongStream.rangeClosed(1, 150).mapToObj(id -> hit(id, 1)).toList();

        assertThat(ReciprocalRankFusion.fuse(candidates, List.of(), 2))
                .extracting(Hit::chunkId).containsExactly(1L, 2L);
        assertThat(ReciprocalRankFusion.fuse(candidates, List.of(), 100)).hasSize(100);
    }

    @Test
    void acceptsEmptyChannelsAndReturnsImmutableResults() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(), 1)).isEmpty();
        List<Hit> result = ReciprocalRankFusion.fuse(List.of(), List.of(hit(8, 1)), 1);
        assertThat(result).extracting(Hit::chunkId).containsExactly(8L);
        assertThatThrownBy(() -> result.add(hit(9, 1))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullInputsAndInvalidLimits() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(null, List.of(), 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(), null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(Arrays.asList((Hit) null), List.of(), 1))
                .isInstanceOf(NullPointerException.class);
        for (int limit : new int[] {-1, 0, 101}) {
            assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(), List.of(), limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Hit hit(long id, double score) {
        return new Hit(id, id, 1, score);
    }
}
