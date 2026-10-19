package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.runtime.AssistantRunSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelBatchLedgerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;

    @Test void reconstructedObjectsKeepCumulativeCallAndTokenLimits() throws Exception {
        Path path = tempDir.resolve("ledger.json");
        assertThat(new ModelBatchLedger(path, 2, 10_000).reserve(4_000)).isTrue();
        assertThat(new ModelBatchLedger(path, 2, 10_000).reserve(6_000)).isTrue();
        assertThat(new ModelBatchLedger(path, 2, 10_000).reserve(1)).isFalse();

        var stored = JSON.readTree(path.toFile());
        assertThat(stored.path("calls").asInt()).isEqualTo(2);
        assertThat(stored.path("reservedTokens").asLong()).isEqualTo(10_000);
    }

    @Test void rejectedReservationDoesNotIncreasePersistedCounters() throws Exception {
        Path path = tempDir.resolve("ledger.json");
        var ledger = new ModelBatchLedger(path, 3, 10);
        assertThat(ledger.reserve(6)).isTrue();
        assertThat(ledger.reserve(5)).isFalse();

        var stored = JSON.readTree(path.toFile());
        assertThat(stored.path("calls").asInt()).isEqualTo(1);
        assertThat(stored.path("reservedTokens").asLong()).isEqualTo(6);
    }

    @Test void fullCorruptedAndDirectoryLedgersCannotTriggerModelCall() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        Path full = tempDir.resolve("full.json");
        var fullLedger = new ModelBatchLedger(full, 1, 100);
        assertThat(fullLedger.reserve(100)).isTrue();
        triggerIfReserved(fullLedger, 1, modelCalls);

        Path corrupted = tempDir.resolve("corrupted.json");
        Files.writeString(corrupted, "{not-json");
        assertThatIllegalStateException().isThrownBy(() -> triggerIfReserved(
                new ModelBatchLedger(corrupted, 1, 100), 1, modelCalls))
                .withMessage("模型批次台账不可用，停止真实调用")
                .withNoCause();
        assertThat(Files.readString(corrupted)).isEqualTo("{not-json");

        Path directory = Files.createDirectory(tempDir.resolve("directory-ledger"));
        assertThatIllegalStateException().isThrownBy(() -> triggerIfReserved(
                new ModelBatchLedger(directory, 1, 100), 1, modelCalls))
                .withMessage("模型批次台账不可用，停止真实调用")
                .withNoCause();
        assertThat(modelCalls).hasValue(0);
    }

    @Test void cancelledOutcomeKeepsUnknownUsageExplicit() throws Exception {
        Path path = tempDir.resolve("ledger.json");
        var ledger = new ModelBatchLedger(path, 3, 12_000);
        assertThat(ledger.reserve(4_000)).isTrue();
        var unknown = new AssistantRunSummary("request-id", 1, 0, 0, 4_000,
                0, 0, 0, false, "CANCELLED", 25);

        ledger.recordOutcome("CANCELLED", unknown, 12, 1, 0);

        var outcome = JSON.readTree(path.toFile()).path("outcomes").get(0);
        assertThat(outcome.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(outcome.path("usageUnknown").asBoolean()).isTrue();
        assertThat(outcome.has("usage")).isFalse();
    }

    @Test void emptyTruncatedOrInterruptedWritesNeverResetExistingAllowance() throws Exception {
        Path empty = Files.createFile(tempDir.resolve("empty.json"));
        assertThatIllegalStateException().isThrownBy(() -> new ModelBatchLedger(empty, 3, 100).reserve(10))
                .withMessage("模型批次台账不可用，停止真实调用");
        assertThat(Files.size(empty)).isZero();

        Path truncated = tempDir.resolve("truncated.json");
        Files.writeString(truncated, "{\"maxCalls\":3,\"calls\":1");
        assertThatIllegalStateException().isThrownBy(() -> new ModelBatchLedger(truncated, 3, 100).reserve(10))
                .withMessage("模型批次台账不可用，停止真实调用");
        assertThat(Files.readString(truncated)).isEqualTo("{\"maxCalls\":3,\"calls\":1");

        Path duplicate = tempDir.resolve("duplicate.json");
        Files.writeString(duplicate, "{\"maxCalls\":3,\"maxCalls\":3,\"maxReservedTokens\":100,"
                + "\"calls\":1,\"reservedTokens\":40}");
        assertThatIllegalStateException().isThrownBy(() -> new ModelBatchLedger(duplicate, 3, 100).reserve(10))
                .withMessage("模型批次台账不可用，停止真实调用");

        Path trailing = tempDir.resolve("trailing.json");
        Files.writeString(trailing, "{\"maxCalls\":3,\"maxReservedTokens\":100,\"calls\":1,"
                + "\"reservedTokens\":40} trailing");
        assertThatIllegalStateException().isThrownBy(() -> new ModelBatchLedger(trailing, 3, 100).reserve(10))
                .withMessage("模型批次台账不可用，停止真实调用");

        Path existing = tempDir.resolve("existing.json");
        var ledger = new ModelBatchLedger(existing, 3, 100);
        assertThat(ledger.reserve(40)).isTrue();
        Files.createDirectory(existing.resolveSibling(existing.getFileName() + ".pending"));
        assertThatIllegalStateException().isThrownBy(() -> ledger.reserve(20))
                .withMessage("模型批次台账不可用，停止真实调用");
        var stored = JSON.readTree(existing.toFile());
        assertThat(stored.path("calls").asInt()).isEqualTo(1);
        assertThat(stored.path("reservedTokens").asLong()).isEqualTo(40);
    }

    private static void triggerIfReserved(ModelBatchLedger ledger, int tokens, AtomicInteger modelCalls) {
        if (ledger.reserve(tokens)) modelCalls.incrementAndGet();
    }
}
