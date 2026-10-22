package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 可恢复上下文检查点的数据契约及稳定输入摘要。 */
public final class ContextCheckpoint {
    public static final String GENESIS = "0".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContextCheckpoint() { }

    public record Snapshot(long revision, long floorSeq, long coveredFromSeq, long coveredThroughSeq,
                           String coveredInputHash, String summaryJson, String dependenciesJson,
                           String provenanceJson, int schemaVersion, String promptVersion,
                           String modelIdentity, String sourceRunId, String gapReason) { }

    public record Event(ChatPersistenceService.Message message, String runStatus,
                        String userMessageId, String assistantMessageId) {
        public Event {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(runStatus, "runStatus");
            Objects.requireNonNull(userMessageId, "userMessageId");
        }
    }

    public record BoundaryProof(long seq, String eventHash) { }

    public static BoundaryProof boundaryProof(Event event) {
        ObjectNode canonical = JSON.createObjectNode();
        canonicalEvent(canonical, event);
        return new BoundaryProof(event.message().seq(), hash(canonical));
    }

    public record Pending(long expectedRevision, String expectedHash, long baseThroughSeq,
                          long floorSeq, long coveredFromSeq, long coveredThroughSeq,
                          String summaryJson, String dependenciesJson, String provenanceJson,
                          int schemaVersion, String promptVersion, String modelIdentity,
                          String gapReason, List<Event> events) {
        public Pending {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    public static String inputHash(String conversationId, long floorSeq, String previousHash, List<Event> events) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(events, "events");
        ObjectNode root = JSON.createObjectNode();
        root.put("conversationId", conversationId);
        root.put("floorSeq", floorSeq);
        root.put("previousHash", previousHash);
        ArrayNode canonicalEvents = root.putArray("events");
        for (Event event : events) canonicalEvent(canonicalEvents.addObject(), event);
        return hash(root);
    }

    public static String genesisHash(String conversationId, long floorSeq, Event barrier) {
        if (floorSeq == 0) return GENESIS;
        ObjectNode root = JSON.createObjectNode();
        root.put("conversationId", Objects.requireNonNull(conversationId, "conversationId"));
        root.put("floorSeq", floorSeq);
        canonicalEvent(root.putObject("barrier"), Objects.requireNonNull(barrier, "barrier"));
        return hash(root);
    }

    private static void canonicalEvent(ObjectNode value, Event event) {
        ChatPersistenceService.Message message = Objects.requireNonNull(event, "event").message();
        value.put("id", message.id());
        value.put("seq", message.seq());
        value.put("runId", message.runId());
        value.put("role", message.role());
        value.put("status", message.status());
        value.put("content", message.content());
        if (message.sourcesJson() == null) value.putNull("sourcesJson");
        else value.put("sourcesJson", message.sourcesJson());
        value.put("runStatus", event.runStatus());
        value.put("userMessageId", event.userMessageId());
        if (event.assistantMessageId() == null) value.putNull("assistantMessageId");
        else value.put("assistantMessageId", event.assistantMessageId());
    }

    private static String hash(ObjectNode root) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(root)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("检查点输入序列化失败", impossible);
        }
    }
}
