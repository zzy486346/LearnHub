package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learnhub.assistant.runtime.AssistantRunSummary;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** 真实模型烟测的持久批次额度；台账不可读写时一律拒绝继续调用模型。 */
final class ModelBatchLedger {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Object JVM_LOCK = new Object();
    private static final String ERROR = "模型批次台账不可用，停止真实调用";

    private final Path path;
    private final int maxCalls;
    private final long maxReservedTokens;

    ModelBatchLedger(Path path, int maxCalls, long maxReservedTokens) {
        if (path == null || maxCalls <= 0 || maxReservedTokens <= 0) {
            throw new IllegalArgumentException("模型批次台账参数无效");
        }
        this.path = path.toAbsolutePath().normalize();
        this.maxCalls = maxCalls;
        this.maxReservedTokens = maxReservedTokens;
    }

    boolean reserve(int tokens) {
        if (tokens <= 0) throw new IllegalArgumentException("预留令牌数必须为正数");
        return edit(root -> {
            int calls = requiredNonNegativeInt(root, "calls");
            long reservedTokens = requiredNonNegativeLong(root, "reservedTokens");
            if (calls > maxCalls || reservedTokens > maxReservedTokens) throw new LedgerCorruptedException();
            if (calls >= maxCalls || tokens > maxReservedTokens - reservedTokens) return false;
            root.put("calls", calls + 1);
            root.put("reservedTokens", reservedTokens + tokens);
            root.put("lastReservationAt", Instant.now().toString());
            return true;
        });
    }

    void recordOutcome(String status, AssistantRunSummary summary, long firstDeltaMillis, int deltaBatches, int step) {
        if (status == null || status.isBlank() || summary == null || step < 0 || deltaBatches < 0) {
            throw new IllegalArgumentException("模型结果参数无效");
        }
        edit(root -> {
            ObjectNode item = root.withArray("outcomes").addObject();
            item.put("step", step + 1);
            item.put("status", status);
            item.put("firstDeltaMillis", firstDeltaMillis);
            item.put("deltaBatches", deltaBatches);
            item.put("usageUnknown", !summary.usageComplete());
            if (summary.usageComplete()) item.set("usage", JSON.valueToTree(summary));
            return true;
        });
    }

    private boolean edit(LedgerUpdate update) {
        synchronized (JVM_LOCK) {
            try {
                Path parent = path.getParent();
                if (parent == null) throw new LedgerCorruptedException();
                Files.createDirectories(parent);
                Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
                try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                     var ignored = channel.lock()) {
                    ObjectNode root = read();
                    boolean accepted = update.apply(root);
                    if (accepted) write(root);
                    return accepted;
                }
            } catch (Exception ignored) {
                throw new IllegalStateException(ERROR);
            }
        }
    }

    private ObjectNode read() throws Exception {
        if (Files.notExists(path)) {
            ObjectNode root = JSON.createObjectNode();
            root.put("batch", "langchain-phase3-20261018");
            root.put("maxCalls", maxCalls);
            root.put("maxReservedTokens", maxReservedTokens);
            root.put("calls", 0);
            root.put("reservedTokens", 0);
            return root;
        }
        if (!Files.isRegularFile(path)) throw new LedgerCorruptedException();
        long size = Files.size(path);
        if (size > 1_048_576) throw new LedgerCorruptedException();
        if (size == 0) throw new LedgerCorruptedException();
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != size) throw new LedgerCorruptedException();
        JsonNode parsed = JSON.readerFor(JsonNode.class).readValue(bytes);
        if (!(parsed instanceof ObjectNode root)
                || root.path("maxCalls").asInt(-1) != maxCalls
                || root.path("maxReservedTokens").asLong(-1) != maxReservedTokens) {
            throw new LedgerCorruptedException();
        }
        requiredNonNegativeInt(root, "calls");
        requiredNonNegativeLong(root, "reservedTokens");
        return root;
    }

    private static int requiredNonNegativeInt(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new LedgerCorruptedException();
        }
        return value.intValue();
    }

    private static long requiredNonNegativeLong(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new LedgerCorruptedException();
        }
        return value.longValue();
    }

    private void write(ObjectNode root) throws Exception {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        if (Files.exists(pending)) throw new LedgerCorruptedException();
        try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @FunctionalInterface
    private interface LedgerUpdate {
        boolean apply(ObjectNode root);
    }

    private static final class LedgerCorruptedException extends RuntimeException { }
}
