package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learnhub.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SummaryReservationTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-10-22T12:00:00Z");

    @Test void rejectedBatchLeavesTheLastDailyCallForAnOrdinaryAnswer() {
        var limits = limits(2, 10_000);
        var store = new AtomicFakeStore();
        var guard = guard(store, limits);
        try (var first = guard.open(1L, "ordinary-first", () -> { });
                var second = guard.open(2L, "summary-second", () -> { })) {
            first.reserveModel(100, 100);

            assertThat(second.tryReserveSummary(100, 100, 300, 100)).isFalse();
            assertThat(second.summary().status()).isEqualTo("RUNNING");
            assertThat(second.summary().reservedTokens()).isZero();

            second.reserveModel(300, 100);
            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
        }
    }

    @Test void successfulBatchPrepaysAnswerWithoutASecondDailyReservation() {
        var limits = limits(4, 10_000);
        var store = new AtomicFakeStore();
        var guard = guard(store, limits);
        try (var run = guard.open(7L, "summary-success", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isTrue();
            assertThat(run.summary().modelCalls()).isEqualTo(1);
            assertThat(run.summary().reservedTokens()).isEqualTo(850);
            assertThat(run.summary().prepaidInputTokens()).isEqualTo(500);
            assertThat(run.summary().prepaidOutputTokens()).isEqualTo(200);
            assertThat(run.summary().usageComplete()).isFalse();
            assertThat(AssistantRunGuard.remainingInputReservation(10_000, run.summary(), 200)).isEqualTo(500);
            assertThat(AssistantRunGuard.remainingInputReservation(10_000, run.summary(), 201)).isZero();

            run.recordUsage(80, 20, 100);
            run.reserveModel(450, 180);

            assertThat(run.summary().modelCalls()).isEqualTo(2);
            assertThat(run.summary().reservedTokens()).isEqualTo(850);
            assertThat(run.summary().prepaidInputTokens()).isZero();
            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
            assertThat(store.batchReservations).isEqualTo(1);
            assertThat(store.singleReservations).isZero();
        }
    }

    @Test void answerAbovePrepaidUpperBoundFailsWithoutConsumingHeldReservation() {
        var limits = limits(4, 10_000);
        var store = new AtomicFakeStore();
        var guard = guard(store, limits);
        try (var run = guard.open(7L, "summary-over-upper", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isTrue();

            assertThatThrownBy(() -> run.reserveModel(501, 200))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_BUDGET_EXCEEDED"));

            assertThat(run.summary().modelCalls()).isEqualTo(1);
            assertThat(run.summary().prepaidInputTokens()).isEqualTo(500);
            assertThat(run.summary().prepaidOutputTokens()).isEqualTo(200);
            assertThat(run.summary().reservedTokens()).isEqualTo(850);
            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
        }
    }

    @Test void aSecondSummaryCannotBeReservedAfterThePrepaidAnswerWasConsumed() {
        var store = new AtomicFakeStore();
        try (var run = guard(store, limits(4, 10_000)).open(7L, "summary-once", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isTrue();
            run.recordUsage(80, 20, 100);
            run.reserveModel(450, 180);
            run.recordUsage(200, 100, 300);
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isFalse();
            assertThat(store.batchReservations).isEqualTo(1);
            assertThat(run.summary().modelCalls()).isEqualTo(2);
        }
    }

    @Test void storeWithoutAtomicBatchSupportFailsClosedButStillAllowsOrdinaryReservation() {
        AssistantRunStore legacy = new AssistantRunStore() {
            @Override public AcquireResult acquire(String user, String owner, long lease, int global, int rpm) {
                return AcquireResult.ACQUIRED;
            }
            @Override public ReserveResult reserveDaily(String user, String day, int calls,
                    int tokenLimit, int tokens, long ttl) {
                return ReserveResult.RESERVED;
            }
            @Override public boolean isCancelled(String user, String owner) { return false; }
            @Override public boolean cancel(String user, String owner, long ttl) { return false; }
            @Override public void release(String user, String owner) { }
        };
        var guard = guard(legacy, limits(4, 10_000));
        try (var run = guard.open(7L, "legacy-store", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isFalse();
            run.reserveModel(500, 200);
            assertThat(run.summary().modelCalls()).isOne();
            assertThat(run.summary().reservedTokens()).isEqualTo(700);
        }
    }

    @Test void cancellationWithUnknownUsageKeepsBothCallsAndAllTokensReserved() {
        var limits = limits(4, 10_000);
        var store = new AtomicFakeStore();
        var guard = guard(store, limits);
        try (var run = guard.open(7L, "summary-cancel", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isTrue();
            assertThat(guard.cancel(7L, "summary-cancel")).isTrue();
            assertThatThrownBy(run::check).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getCode()).isEqualTo("ASSISTANT_CANCELLED"));

            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
            assertThat(store.globalTokens("2026-10-22")).isEqualTo(850);
            assertThat(run.summary().reservedTokens()).isEqualTo(850);
            assertThat(run.summary().usageComplete()).isFalse();
        }
    }

    @Test void simultaneousUsersCannotCrossTheGlobalDailyBatchBoundary() throws Exception {
        var limits = limits(2, 10_000);
        var store = new AtomicFakeStore();
        var guard = guard(store, limits);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> reserveTogether(guard, 1L, "batch-a", ready, start));
            Future<Boolean> second = executor.submit(() -> reserveTogether(guard, 2L, "batch-b", ready, start));
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
            assertThat(store.globalTokens("2026-10-22")).isEqualTo(850);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void prepaidAnswerConsumedAfterUtcMidnightDoesNotChargeTheNewDay() {
        var limits = limits(4, 10_000);
        var store = new AtomicFakeStore();
        var clock = new MutableClock(Instant.parse("2026-10-22T23:59:59Z"));
        var guard = new AssistantRunGuard(store, limits, clock);
        try (var run = guard.open(7L, "summary-midnight", () -> { })) {
            assertThat(run.tryReserveSummary(100, 50, 500, 200)).isTrue();
            clock.advance(Duration.ofSeconds(2));
            run.reserveModel(500, 200);

            assertThat(store.globalCalls("2026-10-22")).isEqualTo(2);
            assertThat(store.globalCalls("2026-10-23")).isZero();
        }
    }

    private static boolean reserveTogether(AssistantRunGuard guard, long user, String request,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        try (var run = guard.open(user, request, () -> { })) {
            ready.countDown();
            start.await();
            return run.tryReserveSummary(100, 50, 500, 200);
        }
    }

    private static AssistantLimits limits(int dailyCalls, int dailyTokens) {
        var limits = new AssistantLimits();
        limits.setDailyModelCalls(dailyCalls);
        limits.setDailyReservedTokens(dailyTokens);
        limits.setMaxModelCalls(4);
        limits.setMaxRunTokens(10_000);
        return limits;
    }

    private static AssistantRunGuard guard(AssistantRunStore store, AssistantLimits limits) {
        return new AssistantRunGuard(store, limits, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
    }

    private static final class AtomicFakeStore implements AssistantRunStore {
        private final Map<String, Integer> userCalls = new HashMap<>();
        private final Map<String, Integer> userTokens = new HashMap<>();
        private final Map<String, Integer> globalCalls = new HashMap<>();
        private final Map<String, Integer> globalTokens = new HashMap<>();
        private final Set<String> cancelled = new HashSet<>();
        private int batchReservations;
        private int singleReservations;

        @Override public AcquireResult acquire(String user, String owner, long lease, int global, int rpm) {
            return AcquireResult.ACQUIRED;
        }

        @Override public synchronized ReserveResult reserveDaily(String user, String day, int callLimit,
                int tokenLimit, int tokens, long ttl) {
            singleReservations++;
            return reserve(user, day, callLimit, tokenLimit, tokens, 1);
        }

        @Override public synchronized ReserveResult reserveDailyBatch(String user, String day, int callLimit,
                int tokenLimit, int tokens, long ttl, int callIncrement) {
            batchReservations++;
            return reserve(user, day, callLimit, tokenLimit, tokens, callIncrement);
        }

        private ReserveResult reserve(String user, String day, int callLimit, int tokenLimit,
                int tokens, int callIncrement) {
            String userDay = day + ":" + user;
            if (userCalls.getOrDefault(userDay, 0) + callIncrement > callLimit
                    || globalCalls.getOrDefault(day, 0) + callIncrement > callLimit) {
                return ReserveResult.DAILY_CALL_LIMIT;
            }
            if (userTokens.getOrDefault(userDay, 0) + tokens > tokenLimit
                    || globalTokens.getOrDefault(day, 0) + tokens > tokenLimit) {
                return ReserveResult.DAILY_TOKEN_LIMIT;
            }
            userCalls.merge(userDay, callIncrement, Integer::sum);
            userTokens.merge(userDay, tokens, Integer::sum);
            globalCalls.merge(day, callIncrement, Integer::sum);
            globalTokens.merge(day, tokens, Integer::sum);
            return ReserveResult.RESERVED;
        }

        @Override public synchronized boolean isCancelled(String user, String owner) {
            return cancelled.contains(owner);
        }
        @Override public synchronized boolean cancel(String user, String owner, long ttl) {
            cancelled.add(owner);
            return true;
        }
        @Override public void release(String user, String owner) { }
        synchronized int globalCalls(String day) { return globalCalls.getOrDefault(day, 0); }
        synchronized int globalTokens(String day) { return globalTokens.getOrDefault(day, 0); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
