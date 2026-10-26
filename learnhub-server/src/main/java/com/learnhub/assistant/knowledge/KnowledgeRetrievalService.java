package com.learnhub.assistant.knowledge;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.assistant.knowledge.vector.ReciprocalRankFusion;
import com.learnhub.assistant.knowledge.rerank.KnowledgeReranker;
import com.learnhub.assistant.runtime.AssistantIoScope;
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
import org.springframework.beans.factory.annotation.Value;

@Service
public class KnowledgeRetrievalService {
    public static final double DEFAULT_MIN_SEMANTIC_SCORE = 0.815;
    private final KnowledgeStore store;
    private final KnowledgeSearchGateway gateway;
    private final CourseService courses;
    private final Supplier<KnowledgeVectorSearch> vectors;
    private final double minSemanticScore;
    private final Supplier<KnowledgeReranker> rerankers;
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses) {
        this.store = store; this.gateway = gateway; this.courses = courses; this.vectors = () -> null;
        this.minSemanticScore = DEFAULT_MIN_SEMANTIC_SCORE;
        this.rerankers = () -> null;
    }
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses,
                                     ObjectProvider<KnowledgeVectorSearch> vectors) {
        this(store, gateway, courses, vectors, DEFAULT_MIN_SEMANTIC_SCORE);
    }
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses,
                                     ObjectProvider<KnowledgeVectorSearch> vectors,
                                     double minSemanticScore) {
        this(store, gateway, courses, vectors, minSemanticScore, null);
    }
    @Autowired
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses,
                                     ObjectProvider<KnowledgeVectorSearch> vectors,
                                     @Value("${learnhub.vector.min-semantic-score:0.815}") double minSemanticScore,
                                     ObjectProvider<KnowledgeReranker> rerankers) {
        if (!Double.isFinite(minSemanticScore) || minSemanticScore < 0 || minSemanticScore > 1)
            throw new IllegalArgumentException("语义分数门槛须为0至1之间的有限数值");
        this.store = store; this.gateway = gateway; this.courses = courses; this.vectors = vectors::getIfAvailable;
        this.minSemanticScore = minSemanticScore;
        this.rerankers = rerankers == null ? () -> null : rerankers::getIfAvailable;
    }

    public Result retrieve(Long userId, long courseId, String question, int limit) {
        if (question == null || question.isBlank() || question.length() > 500 || limit < 1 || limit > 10 || courseId <= 0) {
            throw new BusinessException("KNOWLEDGE_INPUT_INVALID", "问题须为1至500字，返回条数为1至10，课程编号须为正数");
        }
        List<Long> readable = courses.readableLessonIds(userId, courseId);
        if (readable.isEmpty()) return result(List.of(), false, "NONE", rerankers.get() == null ? "DISABLED" : "SKIPPED");
        var candidates = candidates(courseId, readable, question.trim());
        var hits = candidates.hits();
        // 候选阶段之后再次计算当前权限，处理退款、下架或课时删除。
        Set<Long> currentReadable = new HashSet<>(courses.readableLessonIds(userId, courseId));
        var chunks = store.currentChunks(hits.stream().map(KnowledgeSearchGateway.Hit::chunkId).distinct().toList())
                .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, Function.identity()));
        List<VerifiedCandidate> verified = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var hit : hits) {
            var chunk = chunks.get(hit.chunkId());
            if (chunk == null) continue;
            if (chunk.courseId() != courseId || !currentReadable.contains(chunk.lessonId())
                    || chunk.documentId() != hit.documentId() || chunk.sourceVersion() != hit.sourceVersion()
                    || !seen.add(chunk.id())) continue;
            verified.add(new VerifiedCandidate(hit, chunk));
        }
        var reranker = rerankers.get();
        if (reranker == null) return result(sources(verified, limit), candidates.candidateLimitReached(), candidates.mode(), "DISABLED");
        if (verified.size() < 2) return result(sources(verified, limit), candidates.candidateLimitReached(), candidates.mode(), "SKIPPED");
        List<VerifiedCandidate> ordered = verified;
        String rerankMode;
        try {
            checkScope();
            int windowSize = reranker.candidateLimit();
            if (windowSize < 2 || windowSize > 50) throw new IllegalArgumentException("Invalid rerank window");
            windowSize = Math.min(verified.size(), windowSize);
            var window = verified.subList(0, windowSize);
            var ranked = reranker.rerank(question.trim(), window.stream().map(item -> item.chunk().content()).toList());
            checkScope();
            ordered = reorder(verified, ranked, windowSize);
            rerankMode = "APPLIED";
        } catch (RuntimeException failure) {
            propagateTermination(failure);
            rerankMode = "FALLBACK";
        }
        // 上传前的快照不能越过外部调用继续使用；普通故障降级也须再次复核。
        checkScope();
        var readableAfter = new HashSet<>(courses.readableLessonIds(userId, courseId));
        var chunksAfter = store.currentChunks(verified.stream().map(item -> item.chunk().id()).toList())
                .stream().collect(Collectors.toMap(CurrentKnowledgeChunk::id, Function.identity()));
        var current = ordered.stream().filter(item -> readableAfter.contains(item.chunk().lessonId())
                && item.chunk().equals(chunksAfter.get(item.chunk().id()))).toList();
        return result(sources(current, limit), candidates.candidateLimitReached(), candidates.mode(), rerankMode);
    }

    private static List<VerifiedCandidate> reorder(List<VerifiedCandidate> original,
            List<KnowledgeReranker.Ranked> ranked, int size) {
        if (ranked == null || ranked.size() != size) throw new IllegalArgumentException("Incomplete rerank window");
        Set<Integer> seen = new HashSet<>();
        for (var item : ranked) {
            if (item == null || item.index() < 0 || item.index() >= size || !seen.add(item.index())
                    || !Double.isFinite(item.score())) throw new IllegalArgumentException("Invalid rerank result");
        }
        List<VerifiedCandidate> result = new ArrayList<>(original.size());
        ranked.stream().sorted(java.util.Comparator.comparingDouble(KnowledgeReranker.Ranked::score).reversed()
                .thenComparingInt(KnowledgeReranker.Ranked::index)).forEach(item -> result.add(original.get(item.index())));
        result.addAll(original.subList(size, original.size()));
        return result;
    }

    private static List<Source> sources(List<VerifiedCandidate> candidates, int limit) {
        return candidates.stream().limit(limit).map(item -> {
            var chunk = item.chunk();
            return new Source(Long.toString(chunk.documentId()), chunk.sourceVersion(), Long.toString(chunk.id()),
                    Long.toString(chunk.courseId()), Long.toString(chunk.lessonId()), chunk.title(), chunk.heading(),
                    chunk.chunkOrder(), chunk.content(), item.hit().score());
        }).toList();
    }

    private static void checkScope() {
        if (AssistantIoScope.current() != null) AssistantIoScope.current().remaining();
    }

    private static void propagateTermination(RuntimeException failure) {
        if (failure instanceof BusinessException business && ("ASSISTANT_CANCELLED".equals(business.getCode())
                || "ASSISTANT_DEADLINE_EXCEEDED".equals(business.getCode()))) throw business;
    }

    private record VerifiedCandidate(KnowledgeSearchGateway.Hit hit, CurrentKnowledgeChunk chunk) { }

    private Candidates candidates(long courseId, List<Long> readable, String question) {
        var vector = vectors.get();
        if (vector == null || !vector.hybridEnabled())
            return new Candidates(gateway.search(courseId, readable, question, 100), "BM25");
        List<KnowledgeSearchGateway.Hit> bm25 = List.of();
        try { bm25 = gateway.search(courseId, readable, question, 100); }
        // 向量回填可能只完成部分资料；ES 不可用时不能用不完整投影证明“资料不足”。
        catch (SearchUnavailableException failure) { throw new SearchUnavailableException(null); }
        List<KnowledgeSearchGateway.Hit> semantic;
        List<KnowledgeSearchGateway.Hit> fused;
        try {
            semantic = vector.search(courseId, readable, question, 100);
            fused = ReciprocalRankFusion.fuse(bm25, semantic, 100);
        } catch (RuntimeException failure) {
            // 远程Embedding受请求作用域控制；终止信号不能被误当成向量故障后继续检索/生成。
            propagateTermination(failure);
            return new Candidates(bm25, "BM25_FALLBACK");
        }
        boolean candidateLimitReached = fused.size() == 100;
        // RRF只表达名次；先保留原融合排序，再以同身份的原始语义分数过滤弱证据。
        // 0为显式关闭门槛，便于回退及复现历史基线；不影响BM25及向量异常降级。
        if (minSemanticScore > 0) {
            var qualified = semantic.stream()
                    .filter(hit -> Double.isFinite(hit.score()) && hit.score() >= minSemanticScore && hit.score() <= 1)
                    .collect(Collectors.toMap(hit -> new Identity(hit.chunkId(), hit.documentId(), hit.sourceVersion()),
                            hit -> hit.score(), Math::max));
            fused = fused.stream().filter(hit -> qualified.containsKey(
                    new Identity(hit.chunkId(), hit.documentId(), hit.sourceVersion()))).toList();
        }
        return new Candidates(fused, "HYBRID", candidateLimitReached);
    }

    private Result result(List<Source> sources, boolean candidateLimitReached, String mode, String rerankMode) {
        return new Result(UUID.randomUUID().toString(), List.copyOf(sources), sources.isEmpty(), candidateLimitReached, mode, rerankMode);
    }
    private record Candidates(List<KnowledgeSearchGateway.Hit> hits, String mode, boolean candidateLimitReached) {
        private Candidates(List<KnowledgeSearchGateway.Hit> hits, String mode) {
            this(hits, mode, hits.size() == 100);
        }
    }
    private record Identity(long chunkId, long documentId, long sourceVersion) {}
    public record Result(String requestId, List<Source> sources, boolean noRelevantMaterials,
                         boolean candidateLimitReached, String retrievalMode, String rerankMode) {
        public Result(String requestId, List<Source> sources, boolean noRelevantMaterials,
                boolean candidateLimitReached, String retrievalMode) {
            this(requestId, sources, noRelevantMaterials, candidateLimitReached, retrievalMode, "DISABLED");
        }
        public Result(String requestId, List<Source> sources, boolean noRelevantMaterials, boolean candidateLimitReached) {
            this(requestId, sources, noRelevantMaterials, candidateLimitReached, "BM25");
        }
    }
    public record Source(String documentId, long sourceVersion, String chunkId, String courseId, String lessonId,
                         String title, String heading, int chunkOrder, String text, double score) {}
}
