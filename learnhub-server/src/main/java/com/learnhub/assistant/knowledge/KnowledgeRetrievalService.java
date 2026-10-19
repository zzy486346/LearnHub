package com.learnhub.assistant.knowledge;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.assistant.knowledge.vector.ReciprocalRankFusion;
import com.learnhub.search.SearchUnavailableException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class KnowledgeRetrievalService {
    private final KnowledgeStore store;
    private final KnowledgeSearchGateway gateway;
    private final CourseService courses;
    private final Supplier<KnowledgeVectorSearch> vectors;
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses) {
        this.store = store; this.gateway = gateway; this.courses = courses; this.vectors = () -> null;
    }
    @Autowired
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses,
                                     ObjectProvider<KnowledgeVectorSearch> vectors) {
        this.store = store; this.gateway = gateway; this.courses = courses; this.vectors = vectors::getIfAvailable;
    }

    public Result retrieve(Long userId, long courseId, String question, int limit) {
        if (question == null || question.isBlank() || question.length() > 500 || limit < 1 || limit > 10 || courseId <= 0) {
            throw new BusinessException("KNOWLEDGE_INPUT_INVALID", "问题须为1至500字，返回条数为1至10，课程编号须为正数");
        }
        List<Long> readable = courses.readableLessonIds(userId, courseId);
        if (readable.isEmpty()) return result(List.of(), false, "NONE");
        var candidates = candidates(courseId, readable, question.trim());
        var hits = candidates.hits();
        // 候选阶段之后再次计算当前权限，处理退款、下架或课时删除。
        Set<Long> currentReadable = new HashSet<>(courses.readableLessonIds(userId, courseId));
        var chunks = store.currentChunks(hits.stream().map(KnowledgeSearchGateway.Hit::chunkId).distinct().toList())
                .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, Function.identity()));
        List<Source> sources = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var hit : hits) {
            var chunk = chunks.get(hit.chunkId());
            if (chunk == null) continue;
            if (chunk.courseId() != courseId || !currentReadable.contains(chunk.lessonId())
                    || chunk.documentId() != hit.documentId() || chunk.sourceVersion() != hit.sourceVersion()
                    || !seen.add(chunk.id())) continue;
            sources.add(new Source(Long.toString(chunk.documentId()), chunk.sourceVersion(), Long.toString(chunk.id()),
                    Long.toString(chunk.courseId()), Long.toString(chunk.lessonId()), chunk.title(), chunk.heading(),
                    chunk.chunkOrder(), chunk.content(), hit.score()));
            if (sources.size() == limit) break;
        }
        return result(sources, hits.size() == 100, candidates.mode());
    }

    private Candidates candidates(long courseId, List<Long> readable, String question) {
        var vector = vectors.get();
        if (vector == null || !vector.hybridEnabled())
            return new Candidates(gateway.search(courseId, readable, question, 100), "BM25");
        List<KnowledgeSearchGateway.Hit> bm25 = List.of();
        try { bm25 = gateway.search(courseId, readable, question, 100); }
        // 向量回填可能只完成部分资料；ES 不可用时不能用不完整投影证明“资料不足”。
        catch (SearchUnavailableException failure) { throw new SearchUnavailableException(null); }
        try {
            var semantic = vector.search(courseId, readable, question, 100);
            return new Candidates(ReciprocalRankFusion.fuse(bm25, semantic, 100), "HYBRID");
        } catch (RuntimeException failure) {
            return new Candidates(bm25, "BM25_FALLBACK");
        }
    }

    private Result result(List<Source> sources, boolean candidateLimitReached, String mode) {
        return new Result(UUID.randomUUID().toString(), List.copyOf(sources), sources.isEmpty(), candidateLimitReached, mode);
    }
    private record Candidates(List<KnowledgeSearchGateway.Hit> hits, String mode) {}
    public record Result(String requestId, List<Source> sources, boolean noRelevantMaterials,
                         boolean candidateLimitReached, String retrievalMode) {
        public Result(String requestId, List<Source> sources, boolean noRelevantMaterials, boolean candidateLimitReached) {
            this(requestId, sources, noRelevantMaterials, candidateLimitReached, "BM25");
        }
    }
    public record Source(String documentId, long sourceVersion, String chunkId, String courseId, String lessonId,
                         String title, String heading, int chunkOrder, String text, double score) {}
}
