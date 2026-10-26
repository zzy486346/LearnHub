package com.learnhub.assistant.knowledge.rerank;

import java.util.List;

/** 对已经过授权/版本复核的正文窗口排序；index只能映射到本次输入，不能引入新资料。 */
public interface KnowledgeReranker {
    int candidateLimit();
    List<Ranked> rerank(String query, List<String> documents);
    record Ranked(int index, double score) { }
}
