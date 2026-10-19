package com.learnhub.assistant.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import java.util.*;

/** 框架只编排检索与注入；身份取自本轮服务端scope，授权检索仍由业务facade决定。 */
public final class AuthorizedCourseRag {
    private final KnowledgeRetrievalService retrieval;
    private final ObjectMapper mapper;

    public AuthorizedCourseRag(KnowledgeRetrievalService retrieval, ObjectMapper mapper) {
        this.retrieval = retrieval;
        this.mapper = mapper;
    }

    public Result augment(Scope scope, String question, String retrievalQuery,
                          List<ChatMessage> history, List<KnowledgeRetrievalService.Source> historySources) {
        var book = scope.book();
        Map<String, KnowledgeRetrievalService.Source> sources = new LinkedHashMap<>();
        historySources.forEach(source -> register(book, sources, source));
        ContentRetriever authorized = query -> {
            scope.guard().check();
            book.check();
            return retrieval.retrieve(scope.owner(), scope.courseId(), query.text(), 5).sources().stream()
                    .map(source -> {
                        String id = register(book, sources, source);
                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("evidenceId", id);
                        fields.put("documentId", source.documentId());
                        fields.put("sourceVersion", source.sourceVersion());
                        fields.put("chunkId", source.chunkId());
                        fields.put("courseId", source.courseId());
                        fields.put("lessonId", source.lessonId());
                        fields.put("title", Objects.toString(source.title(), ""));
                        fields.put("heading", Objects.toString(source.heading(), ""));
                        fields.put("chunkOrder", source.chunkOrder());
                        fields.put("score", source.score());
                        return Content.from(TextSegment.from(source.text(),
                                dev.langchain4j.data.document.Metadata.from(fields)));
                    }).toList();
        };
        var original = UserMessage.from(question);
        var augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(query -> List.of(Query.from(retrievalQuery, query.metadata())))
                .contentRetriever(authorized)
                // 同一已有有界worker执行；保留IoScope，不使用框架默认cached线程池。
                .executor(Runnable::run).offloadBlocking(false)
                .contentInjector((contents, ignored) -> {
                    scope.guard().check();
                    book.check();
                    for (Content content : contents) {
                        String id = content.textSegment().metadata().getString("evidenceId");
                        if (id == null || !book.contains(id)) throw invalid();
                        var observation = mapper.valueToTree(book.observation(id));
                        if (!content.textSegment().text().equals(observation.path("data").path("text").asText()))
                            throw invalid();
                    }
                    try {
                        // 整个账本包含本轮召回和已复核历史依赖；不丢弃任何保留历史的来源。
                        return UserMessage.from(mapper.writeValueAsString(Map.of(
                                "question", question, "evidence", book.context())));
                    } catch (Exception malformed) { throw invalid(); }
                }).build();
        dev.langchain4j.rag.AugmentationResult augmented;
        try {
            augmented = augmentor.augment(new AugmentationRequest(original,
                    Metadata.from(original, scope.owner() + ":" + scope.courseId() + ":" + scope.conversationId(), history)));
        } catch (java.util.concurrent.CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof RuntimeException failure) throw failure;
            throw wrapped;
        }
        scope.guard().check();
        book.check();
        return new Result(augmented.chatMessage(), List.copyOf(sources.values()), List.copyOf(augmented.contents()));
    }

    private static String register(EvidenceBook book, Map<String, KnowledgeRetrievalService.Source> sources,
                                   KnowledgeRetrievalService.Source source) {
        String id = book.material(source); //稳定字段检查忽略检索score，但不会忽略版本/课时/正文。
        sources.putIfAbsent(source.chunkId(), source);
        return id;
    }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_OUTPUT_INVALID", "课程检索证据注入失败");
    }

    public record Scope(long owner, long courseId, String conversationId,
                        AssistantRunGuard.Run guard, EvidenceBook book) {
        public Scope {
            if (owner <= 0 || courseId <= 0 || conversationId == null || conversationId.isBlank()
                    || guard == null || book == null) throw new IllegalArgumentException("invalid authorized RAG scope");
        }
    }
    public record Result(ChatMessage message, List<KnowledgeRetrievalService.Source> sources, List<Content> contents) { }
}
