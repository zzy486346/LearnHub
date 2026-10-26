package com.learnhub.assistant.knowledge.vector;

import dev.langchain4j.data.embedding.Embedding;
import java.util.List;

/** 同一投影代次的文档和查询必须使用相同模型身份、分片规则和维度。 */
public interface CourseEmbedding extends AutoCloseable {
    int MAX_PARTS_PER_CHUNK = 32;
    String modelId();
    String tableName();
    int dimension();
    List<String> splitDocument(String text);
    List<Embedding> embedDocuments(List<String> texts);
    Embedding embedQuery(String question);
    long embeddingCount();
    @Override void close();
}
