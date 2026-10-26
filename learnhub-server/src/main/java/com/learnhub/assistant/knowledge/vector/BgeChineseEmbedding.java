package com.learnhub.assistant.knowledge.vector;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 仅按需创建；不注册为默认组件，停用向量功能时不加载原生模型。 */
public final class BgeChineseEmbedding implements CourseEmbedding {
    public static final String MODEL_ID = "bge-zh-v15-q-lc122-t480-o32-v1";
    public static final int MAX_PARTS_PER_CHUNK = 32;
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private final BgeSmallZhV15QuantizedEmbeddingModel model;
    private final HuggingFaceTokenizer tokenizer;
    private long embeddingCount;
    private boolean closed;

    public BgeChineseEmbedding() {
        // 此应用仅使用打包资源；DJL 默认会探测 EC2 元数据，必须在初始化前禁用。
        System.setProperty("ai.djl.offline", "true");
        System.setProperty("OPT_OUT_TRACKING", "true");
        if (!ai.djl.util.Utils.isOfflineMode())
            throw new IllegalStateException("DJL_OFFLINE must not disable local-only embedding mode");
        try (var input = BgeChineseEmbedding.class.getResourceAsStream("/bge-small-zh-v1.5-q-tokenizer.json")) {
            if (input == null) throw new IllegalStateException("BGE tokenizer resource missing");
            tokenizer = HuggingFaceTokenizer.newInstance(input, Map.of(
                    "truncation", "false", "padding", "false", "addSpecialTokens", "true"));
        } catch (IOException e) {
            throw new IllegalStateException("BGE tokenizer initialization failed", e);
        }
        // 避免 SDK 默认线程池；其静态 ONNX 会话随 JVM 生命周期释放，SDK 无公开 close。
        try { model = new BgeSmallZhV15QuantizedEmbeddingModel(Runnable::run); }
        catch (RuntimeException | Error failure) { tokenizer.close(); throw failure; }
    }

    public String modelId() { return MODEL_ID; }
    public String tableName() { return CourseVectorIndex.TABLE; }
    public int dimension() { return model.dimension(); }
    public synchronized long embeddingCount() { return embeddingCount; }

    /** 包含 CLS/SEP，禁用截断后计算实际 Token 数。 */
    public synchronized int countTokens(String text) {
        requireOpen();
        validate(text);
        return tokenizer.encode(text).getIds().length;
    }

    /** 输入是阶段 4 的单个切片，最多 600 UTF-16 单元；投影可以再分片。 */
    public synchronized List<String> splitDocument(String text) {
        requireOpen();
        validate(text);
        if (text.isBlank() || text.length() > 600) throw new IllegalArgumentException("Invalid document chunk");
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.length();
            // 不依赖 WordPiece token 数的单调性；每个实际片段独立验证。
            while (countTokens(text.substring(start, end)) > 480) {
                end = text.offsetByCodePoints(end, -1);
                if (end == start) throw new IllegalStateException("Cannot split document");
            }
            parts.add(text.substring(start, end));
            if (parts.size() > MAX_PARTS_PER_CHUNK) throw new IllegalArgumentException("Document part limit exceeded");
            if (end == text.length()) break;
            int next = end;
            for (int candidate = text.offsetByCodePoints(end, -1); candidate > start;
                    candidate = text.offsetByCodePoints(candidate, -1)) {
                if (countTokens(text.substring(candidate, end)) > 32) break;
                next = candidate;
            }
            start = next;
        }
        return List.copyOf(parts);
    }

    public synchronized List<Embedding> embedDocuments(List<String> texts) {
        requireOpen();
        if (texts == null || texts.isEmpty() || texts.size() > 32) throw new IllegalArgumentException("Invalid embedding batch");
        for (String text : texts) {
            if (text == null || text.isBlank() || text.length() > 600 || countTokens(text) > 480)
                throw new IllegalArgumentException("Document token limit exceeded");
        }
        var result = model.embedAll(texts.stream().map(TextSegment::from).toList()).content();
        embeddingCount += result.size();
        return List.copyOf(result);
    }

    public synchronized Embedding embedQuery(String question) {
        requireOpen();
        validate(question);
        if (question.isBlank() || question.length() > 500) throw new IllegalArgumentException("Invalid embedding query");
        String input = QUERY_PREFIX + question;
        if (countTokens(input) > 512) throw new IllegalArgumentException("Query token limit exceeded");
        Embedding result = model.embed(input).content();
        embeddingCount++;
        return result;
    }

    private static void validate(String text) {
        if (text == null) throw new IllegalArgumentException("Text is required");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode text");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid Unicode text");
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Embedding component closed");
    }

    @Override public synchronized void close() {
        if (!closed) { closed = true; tokenizer.close(); }
    }
}
