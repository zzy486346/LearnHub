package com.learnhub.assistant.knowledge;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalService {
    private final KnowledgeStore store;
    private final KnowledgeSearchGateway gateway;
    private final CourseService courses;
    public KnowledgeRetrievalService(KnowledgeStore store, KnowledgeSearchGateway gateway, CourseService courses) {
        this.store = store; this.gateway = gateway; this.courses = courses;
    }

    public Result retrieve(Long userId, long courseId, String question, int limit) {
        if (question == null || question.isBlank() || question.length() > 500 || limit < 1 || limit > 10 || courseId <= 0) {
            throw new BusinessException("KNOWLEDGE_INPUT_INVALID", "问题须为1至500字，返回条数为1至10，课程编号须为正数");
        }
        List<Long> readable = courses.readableLessonIds(userId, courseId);
        if (readable.isEmpty()) return result(List.of(), false);
        var hits = gateway.search(courseId, readable, question.trim(), 100);
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
        return result(sources, hits.size() == 100);
    }

    private Result result(List<Source> sources, boolean candidateLimitReached) {
        return new Result(UUID.randomUUID().toString(), List.copyOf(sources), sources.isEmpty(), candidateLimitReached);
    }
    public record Result(String requestId, List<Source> sources, boolean noRelevantMaterials, boolean candidateLimitReached) {}
    public record Source(String documentId, long sourceVersion, String chunkId, String courseId, String lessonId,
                         String title, String heading, int chunkOrder, String text, double score) {}
}
