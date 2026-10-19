package com.learnhub.assistant.knowledge.vector;

import com.learnhub.assistant.knowledge.KnowledgeSearchGateway;
import java.util.List;

public interface KnowledgeVectorSearch {
    boolean hybridEnabled();
    List<KnowledgeSearchGateway.Hit> search(long courseId, List<Long> readableLessonIds, String question, int limit);
}
