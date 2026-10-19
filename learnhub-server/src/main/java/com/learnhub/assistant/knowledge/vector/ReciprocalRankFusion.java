package com.learnhub.assistant.knowledge.vector;

import com.learnhub.assistant.knowledge.KnowledgeSearchGateway.Hit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按通道排名融合；原始检索分数不参与跨通道比较。 */
public final class ReciprocalRankFusion {
    public static final int RANK_CONSTANT = 60;
    public static final int MAX_RESULTS = 100;

    private ReciprocalRankFusion() {}

    public static List<Hit> fuse(List<Hit> bm25, List<Hit> vector, int limit) {
        Objects.requireNonNull(bm25, "BM25 候选不能为空");
        Objects.requireNonNull(vector, "向量候选不能为空");
        if (limit < 1 || limit > MAX_RESULTS) {
            throw new IllegalArgumentException("融合结果数量必须在 1 到 100 之间");
        }

        Map<Long, Hit> identities = new LinkedHashMap<>();
        Set<Long> conflicts = new HashSet<>();
        checkIdentities(bm25, identities, conflicts);
        checkIdentities(vector, identities, conflicts);

        Map<Long, Double> scores = new LinkedHashMap<>();
        addChannel(bm25, conflicts, scores);
        addChannel(vector, conflicts, scores);
        List<Hit> result = new ArrayList<>(scores.size());
        scores.forEach((chunkId, score) -> {
            Hit identity = identities.get(chunkId);
            result.add(new Hit(chunkId, identity.documentId(), identity.sourceVersion(), score));
        });
        // List.sort 保持相同分数候选的首次出现顺序。
        result.sort(Comparator.comparingDouble(Hit::score).reversed());
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    private static void checkIdentities(List<Hit> hits, Map<Long, Hit> identities, Set<Long> conflicts) {
        for (Hit hit : hits) {
            Objects.requireNonNull(hit, "检索候选不能为空");
            Hit first = identities.putIfAbsent(hit.chunkId(), hit);
            if (first != null && (first.documentId() != hit.documentId()
                    || first.sourceVersion() != hit.sourceVersion())) {
                conflicts.add(hit.chunkId());
            }
        }
    }

    private static void addChannel(List<Hit> hits, Set<Long> conflicts, Map<Long, Double> scores) {
        Set<Long> seen = new HashSet<>();
        int rank = 0;
        for (Hit hit : hits) {
            if (conflicts.contains(hit.chunkId()) || !seen.add(hit.chunkId())) continue;
            rank++;
            scores.merge(hit.chunkId(), 1.0 / (RANK_CONSTANT + rank), Double::sum);
        }
    }
}
