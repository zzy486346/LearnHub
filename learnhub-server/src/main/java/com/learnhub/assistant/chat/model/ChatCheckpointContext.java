package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.chat.application.ContextCheckpoint.*;
import com.learnhub.assistant.qa.*;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只恢复已复核快照；扫描范围与语义覆盖范围分别保留，不把未读区间当成已覆盖。 */
final class ChatCheckpointContext {
    private final ChatPersistenceService persistence;
    private final ChatHistoryService history;
    private final CourseQaService qa;
    private final ChatEvidenceCodec evidence;
    private final ObjectMapper mapper;
    private final AssistantLimits limits;

    ChatCheckpointContext(ChatPersistenceService persistence, ChatHistoryService history, CourseQaService qa,
            ChatEvidenceCodec evidence, ObjectMapper mapper, AssistantLimits limits) {
        this.persistence = persistence; this.history = history; this.qa = qa;
        this.evidence = evidence; this.mapper = mapper; this.limits = limits;
    }

    Recovered recover(long owner, long course, String conversation, String currentRun, String prompt,
            String model, Runnable check) {
        check.run();
        Snapshot expected = persistence.contextCheckpoint(owner, conversation, course);
        Snapshot valid = null;
        SemanticConversationSummary.Summary summary = null;
        List<SourceProof> provenance = List.of();
        Event barrier = null;
        String reason = "NO_CHECKPOINT";
        if (expected != null) {
            try {
                if (expected.revision() < 1 || expected.schemaVersion() != 1
                        || !prompt.equals(expected.promptVersion()) || !model.equals(expected.modelIdentity())
                        || expected.floorSeq() < 0 || expected.coveredFromSeq() <= expected.floorSeq()
                        || expected.coveredThroughSeq() < expected.coveredFromSeq()
                        || expected.coveredInputHash() == null || !expected.coveredInputHash().matches("[0-9a-f]{64}")) throw invalid();
                Provenance parsed = parseProvenance(mapper, expected.provenanceJson());
                summary = SemanticConversationSummary.parse(mapper, expected.summaryJson(), allowed(parsed.sources()), userSeqs(parsed.sources()));
                if (!referenced(summary).equals(allowed(parsed.sources()).keySet())) throw invalid();
                Event boundary = at(owner, conversation, expected.coveredThroughSeq());
                if (!completedAssistant(boundary) || !ContextCheckpoint.boundaryProof(boundary).equals(parsed.boundary())) throw invalid();
                var published = persistence.run(owner, expected.sourceRunId());
                if (published == null || published.ownerUserId() != owner || !conversation.equals(published.conversationId())
                        || !"COMPLETED".equals(published.status())) throw invalid();
                barrier = parsed.barrier();
                if (expected.floorSeq() == 0 && barrier != null) throw invalid();
                if (expected.floorSeq() > 0) {
                    Event currentBarrier = at(owner, conversation, expected.floorSeq());
                    if (barrier == null || !barrier.equals(currentBarrier) || !completedAssistant(currentBarrier)) throw invalid();
                    // 暂时故障导致的屏障一旦恢复可读，重新从原始历史重建，不能永久丢弃旧前缀。
                    if (!history.verify(owner, course, currentBarrier.message()).restricted()) throw invalid();
                }
                for (SourceProof proof : parsed.sources()) {
                    check.run();
                    if (proof.seq() <= expected.floorSeq() || proof.seq() > expected.coveredThroughSeq()) throw invalid();
                    Event event = at(owner, conversation, proof.seq());
                    if (!proof.equals(proof(event.message())) || !"COMPLETED".equals(event.runStatus())
                            || "USER".equals(proof.role()) && !event.userMessageId().equals(event.message().id())
                            || "ASSISTANT".equals(proof.role()) && !completedAssistant(event)) throw invalid();
                }
                var validationBook = qa.evidenceBook(owner, course);
                restore(owner, course, expected.dependenciesJson(), validationBook);
                check.run();
                valid = expected; provenance = parsed.sources(); reason = "CHECKPOINT_RESTORED";
            } catch (BusinessException failure) {
                if (terminal(failure)) throw failure;
                summary = null; barrier = null; reason = "CHECKPOINT_INVALID";
            } catch (RuntimeException malformedOrUnavailable) {
                check.run(); summary = null; barrier = null; reason = "CHECKPOINT_INVALID";
            }
        }
        long floor = valid == null ? 0 : valid.floorSeq();
        long cursor = valid == null ? 0 : valid.coveredThroughSeq();
        List<Event> events = new ArrayList<>();
        List<ChatPersistenceService.Message> messages = new ArrayList<>();
        Event pending = null;
        int scanned = 0;
        long bytes = 0;
        boolean limited = false, stopped = false, ended = false, malformed = false;
        while (!stopped && scanned < limits.getContextRecoveryMaxMessages()) {
            check.run();
            int pageSize = Math.min(48, limits.getContextRecoveryMaxMessages() - scanned);
            List<Event> page = persistence.contextEvents(owner, conversation, cursor, pageSize);
            if (page.isEmpty()) { ended = true; break; }
            for (Event event : page) {
                check.run();
                var message = event.message();
                if (message.seq() != cursor + 1) { malformed = true; limited = true; stopped = true; break; }
                if (currentRun.equals(message.runId()) || "RUNNING".equals(event.runStatus())) { stopped = true; ended = true; break; }
                long nextBytes = message.content().getBytes(StandardCharsets.UTF_8).length
                        + (message.sourcesJson() == null ? 0 : message.sourcesJson().getBytes(StandardCharsets.UTF_8).length) + 256L;
                if (bytes + nextBytes > limits.getContextRecoveryMaxBytes()) { limited = true; stopped = true; break; }
                cursor = message.seq(); scanned++; bytes += nextBytes;
                if (!"COMPLETED".equals(message.status())) { malformed = true; limited = true; stopped = true; break; }
                if ("USER".equals(message.role())) {
                    if (pending != null || !message.id().equals(event.userMessageId())) { malformed = true; limited = true; stopped = true; break; }
                    if (event.assistantMessageId() == null && Set.of("FAILED", "CANCELLED", "INTERRUPTED").contains(event.runStatus())) {
                        events.add(event); continue;
                    }
                    if (!"COMPLETED".equals(event.runStatus()) || event.assistantMessageId() == null) { malformed = true; limited = true; stopped = true; break; }
                    pending = event; events.add(event);
                } else if (completedAssistant(event) && pending != null && pair(pending, event)) {
                    var checked = history.verify(owner, course, message);
                    check.run();
                    events.add(event);
                    if (checked.restricted()) {
                        events.clear(); messages.clear(); valid = null; summary = null; provenance = List.of();
                        floor = message.seq(); barrier = event; reason = "RESTRICTED_PREFIX";
                    } else {
                        messages.add(pending.message()); messages.add(message);
                    }
                    pending = null;
                } else { malformed = true; limited = true; stopped = true; break; }
            }
            if (!stopped && page.size() < pageSize) { ended = true; break; }
        }
        if (!ended && !stopped || pending != null) limited = true;
        check.run();
        return new Recovered(expected, valid, summary, List.copyOf(provenance), List.copyOf(events), List.copyOf(messages),
                floor, barrier, limited, limited ? malformed ? "HISTORY_STRUCTURE_GAP" : "SCAN_LIMIT_GAP" : reason);
    }

    private Event at(long owner, String conversation, long seq) {
        var values = persistence.contextEvents(owner, conversation, seq - 1, 1);
        if (values.size() != 1 || values.get(0).message().seq() != seq) throw invalid();
        return values.get(0);
    }

    private void restore(long owner, long course, String dependencies, EvidenceBook book) {
        if (evidence != null) evidence.restoreInto(owner, course, dependencies, book);
        else {
            try { if (mapper.readTree(dependencies).path("version").asInt(1) > 1) throw invalid(); }
            catch (java.io.IOException malformed) { throw invalid(); }
            for (var entry : ChatEvidenceCodec.parse(dependencies)) {
                if (entry.source() == null) throw invalid();
                book.material(entry.source());
            }
            book.check();
        }
    }

    static SourceProof proof(ChatPersistenceService.Message message) {
        List<String> keys = "USER".equals(message.role()) ? List.of() : ChatEvidenceCodec.parse(message.sourcesJson()).stream()
                .map(ChatEvidenceCodec.ParsedEntry::key).sorted().toList();
        return new SourceProof(message.seq(), message.id(), message.runId(), message.role(), hash(message.content()), keys);
    }

    static Map<Long, Set<String>> allowed(List<SourceProof> proofs) {
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        for (SourceProof proof : proofs) if (result.put(proof.seq(), Set.copyOf(proof.dependencyKeys())) != null) throw invalid();
        return result;
    }

    static Set<Long> userSeqs(List<SourceProof> proofs) {
        Set<Long> result = new LinkedHashSet<>();
        proofs.stream().filter(proof -> "USER".equals(proof.role())).forEach(proof -> result.add(proof.seq()));
        return result;
    }

    static Set<Long> referenced(SemanticConversationSummary.Summary summary) {
        Set<Long> result = new LinkedHashSet<>();
        var state = summary.taskState();
        for (var items : List.of(state.goal(), state.topic(), state.constraints(), state.decisions(), state.corrections(),
                state.openQuestions(), state.nextStep(), summary.conversationSummary(), summary.unresolvedReferences()))
            items.forEach(item -> result.addAll(item.sourceMessageSeqs()));
        return result;
    }

    static String encodeProvenance(ObjectMapper mapper, List<SourceProof> proofs, Event barrier) {
        return encodeProvenance(mapper, proofs, barrier, null);
    }

    static String encodeProvenance(ObjectMapper mapper, List<SourceProof> proofs, Event barrier, Event boundary) {
        try {
            String encoded = mapper.writeValueAsString(new Provenance(1, List.copyOf(proofs), barrier,
                    boundary == null ? null : ContextCheckpoint.boundaryProof(boundary)));
            parseProvenance(mapper, encoded);
            return encoded;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }

    private static Provenance parseProvenance(ObjectMapper mapper, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid();
        try {
            JsonNode root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (!root.isObject() || root.size() != 4 || !root.has("version") || !root.has("sources") || !root.has("barrier") || !root.has("boundary")
                    || !root.path("version").isIntegralNumber() || root.path("version").asInt() != 1 || !root.path("sources").isArray()) throw invalid();
            Provenance value = mapper.readerFor(Provenance.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(root);
            if (value.sources() == null || value.sources().size() > 128) throw invalid();
            Set<Long> seqs = new HashSet<>();
            for (SourceProof proof : value.sources()) {
                if (proof.seq() < 1 || !seqs.add(proof.seq()) || proof.messageId() == null || proof.messageId().isBlank()
                        || proof.runId() == null || proof.runId().isBlank() || !Set.of("USER", "ASSISTANT").contains(proof.role())
                        || proof.contentHash() == null || !proof.contentHash().matches("[0-9a-f]{64}")
                        || proof.dependencyKeys() == null || proof.dependencyKeys().size() > 20
                        || new HashSet<>(proof.dependencyKeys()).size() != proof.dependencyKeys().size()
                        || "USER".equals(proof.role()) && !proof.dependencyKeys().isEmpty()) throw invalid();
            }
            return value;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }

    private static boolean completedAssistant(Event event) {
        return "ASSISTANT".equals(event.message().role()) && "COMPLETED".equals(event.message().status())
                && "COMPLETED".equals(event.runStatus()) && event.message().id().equals(event.assistantMessageId());
    }

    private static boolean pair(Event user, Event assistant) {
        return user.message().runId().equals(assistant.message().runId()) && user.userMessageId().equals(assistant.userMessageId())
                && Objects.equals(user.assistantMessageId(), assistant.assistantMessageId());
    }

    private static boolean terminal(BusinessException failure) {
        return Set.of("ASSISTANT_CANCELLED", "ASSISTANT_DEADLINE_EXCEEDED").contains(failure.getCode());
    }

    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BusinessException invalid() { return new BusinessException("ASSISTANT_CHECKPOINT_INVALID", "上下文检查点结构或来源无效"); }

    record SourceProof(long seq, String messageId, String runId, String role, String contentHash, List<String> dependencyKeys) { }
    private record Provenance(int version, List<SourceProof> sources, Event barrier, BoundaryProof boundary) { }
    record Recovered(Snapshot expected, Snapshot valid, SemanticConversationSummary.Summary summary, List<SourceProof> provenance,
                     List<Event> events, List<ChatPersistenceService.Message> messages, long floorSeq, Event barrier,
                     boolean scanLimited, String reason) { }
}
