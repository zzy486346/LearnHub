package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantRunGuardTest {
    private AssistantRunStore store;
    private AssistantLimits limits;
    private MutableClock clock;
    private AssistantRunGuard guard;

    @BeforeEach
    void setUp() {
        store = mock(AssistantRunStore.class);
        limits = new AssistantLimits();
        clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        guard = new AssistantRunGuard(store, limits, clock);
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt()))
                .thenReturn(AssistantRunStore.AcquireResult.ACQUIRED);
        when(store.reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong()))
                .thenReturn(AssistantRunStore.ReserveResult.RESERVED);
    }

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test void providerReportedOverrunStopsRunAndKeepsActualUsage() {
        try (var run = guard.open(7L, "actual-overrun", () -> {})) {
            run.reserveModel(1, 1);
            assertThatThrownBy(() -> run.recordUsage(24_000, 1, 24_001))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("实际用量");
            assertThat(run.summary().actualTotalTokens()).isEqualTo(24_001);
            assertThat(run.summary().usageComplete()).isTrue();
        }
    }

    @Test
    void successfulRunTracksOnlyCountersAndReleasesLease() {
        AtomicInteger identityChecks = new AtomicInteger();
        AssistantRunGuard.Run run = guard.open(7L, "request-12345678", identityChecks::incrementAndGet);

        run.reserveModel(300, 500);
        run.recordUsage(250, 120, 370);
        run.toolDecisionRound();
        run.toolCall("search_courses:{keyword=Redis,limit=3}");
        clock.advance(Duration.ofSeconds(2));
        run.markSucceeded();
        run.close();

        AssistantRunSummary summary = run.summary();
        assertThat(summary.requestId()).isEqualTo("request-12345678");
        assertThat(summary.modelCalls()).isEqualTo(1);
        assertThat(summary.toolDecisionRounds()).isEqualTo(1);
        assertThat(summary.toolCalls()).isEqualTo(1);
        assertThat(summary.reservedTokens()).isEqualTo(800);
        assertThat(summary.actualTotalTokens()).isEqualTo(370);
        assertThat(summary.usageComplete()).isTrue();
        assertThat(summary.status()).isEqualTo("SUCCEEDED");
        assertThat(summary.elapsedMillis()).isEqualTo(2_000);
        assertThat(identityChecks).hasValue(5);
        verify(store).release(anyString(), anyString());
    }

    @Test
    void openFailsClosedForRedisFailureAndDoesNotCreateRun() {
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertCode(() -> guard.open(7L, "request-12345678", () -> {}), "ASSISTANT_RUNTIME_UNAVAILABLE");
        verify(store, never()).release(anyString(), anyString());
    }

    @Test
    void openMapsConcurrencyAndMinuteRateLimits() {
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt()))
                .thenReturn(AssistantRunStore.AcquireResult.USER_BUSY,
                        AssistantRunStore.AcquireResult.GLOBAL_BUSY,
                        AssistantRunStore.AcquireResult.RATE_LIMITED);

        assertCode(() -> guard.open(7L, "request-12345678", () -> {}), "ASSISTANT_BUSY");
        assertCode(() -> guard.open(7L, "request-22345678", () -> {}), "ASSISTANT_BUSY");
        assertCode(() -> guard.open(7L, "request-32345678", () -> {}), "ASSISTANT_RATE_LIMITED");
    }

    @Test
    void modelBudgetIsReservedBeforeCountersAdvanceAndIsNeverRefunded() {
        limits.setMaxModelCalls(1);
        limits.setMaxRunTokens(1_000);
        AssistantRunGuard.Run run = guard.open(7L, "request-12345678", () -> {});

        run.reserveModel(400, 500);
        assertCode(() -> run.reserveModel(1, 1), "ASSISTANT_BUDGET_EXCEEDED");
        assertThat(run.summary().modelCalls()).isEqualTo(1);
        assertThat(run.summary().reservedTokens()).isEqualTo(900);
        assertThat(run.summary().usageComplete()).isFalse();
        run.close();
        verify(store).reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void rejectedDailyReservationDoesNotAdvanceLocalCounters() {
        when(store.reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong()))
                .thenReturn(AssistantRunStore.ReserveResult.DAILY_TOKEN_LIMIT);
        AssistantRunGuard.Run run = guard.open(7L, "request-12345678", () -> {});

        assertCode(() -> run.reserveModel(400, 500), "ASSISTANT_DAILY_LIMIT");
        assertThat(run.summary().modelCalls()).isZero();
        assertThat(run.summary().reservedTokens()).isZero();
        run.close();
    }

    @Test
    void duplicateToolSignatureAndToolLimitsStopTheLoop() {
        AssistantRunGuard.Run repeated = guard.open(7L, "request-12345678", () -> {});
        repeated.toolCall("get_course_outline:{courseId=1}");
        assertCode(() -> repeated.toolCall(" get_course_outline:{courseId=1} "), "ASSISTANT_TOOL_LOOP");
        repeated.close();

        AssistantLimits oneTool = new AssistantLimits();
        oneTool.setMaxToolCalls(1);
        AssistantRunGuard anotherGuard = new AssistantRunGuard(store, oneTool, clock);
        AssistantRunGuard.Run limited = anotherGuard.open(8L, "request-22345678", () -> {});
        limited.toolCall("search_courses:{}");
        assertCode(() -> limited.toolCall("get_course_outline:{}"), "ASSISTANT_TOOL_LIMIT");
        limited.close();
    }

    @Test
    void interruptionDeadlineCancellationAndIdentityAreRechecked() {
        AssistantRunGuard.Run interrupted = guard.open(7L, "request-12345678", () -> {});
        Thread.currentThread().interrupt();
        assertCode(interrupted::check, "ASSISTANT_CANCELLED");
        Thread.interrupted();
        interrupted.close();

        AssistantRunGuard.Run expired = guard.open(8L, "request-22345678", () -> {});
        clock.advance(limits.getTimeout());
        assertCode(expired::check, "ASSISTANT_DEADLINE_EXCEEDED");
        expired.close();

        clock.advance(limits.getTimeout().negated());
        AssistantRunGuard.Run cancelled = guard.open(9L, "request-32345678", () -> {});
        when(store.isCancelled(anyString(), anyString())).thenReturn(true);
        assertCode(cancelled::check, "ASSISTANT_CANCELLED");
        cancelled.close();

        when(store.isCancelled(anyString(), anyString())).thenReturn(false);
        Runnable revoked = mock(Runnable.class);
        AssistantRunGuard.Run identity = guard.open(10L, "request-42345678", revoked);
        doThrow(new BusinessException("UNAUTHORIZED", "登录状态失效")).when(revoked).run();
        assertCode(identity::check, "UNAUTHORIZED");
        identity.close();
    }

    @Test
    void cancelUsesBothUserAndRequestAndCannotMatchAnotherUser() {
        when(store.cancel(anyString(), anyString(), anyLong())).thenReturn(true, false);

        assertThat(guard.cancel(7L, "request-12345678")).isTrue();
        assertThat(guard.cancel(8L, "request-12345678")).isFalse();

        ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.times(2)).cancel(users.capture(), anyString(), anyLong());
        assertThat(users.getAllValues().get(0)).isNotEqualTo(users.getAllValues().get(1));
    }

    @Test
    void closeNeverReplacesBusinessFailureWithReleaseFailure() {
        AssistantRunGuard.Run run = guard.open(7L, "request-12345678", () -> {});
        doThrow(new IllegalStateException("redis unavailable")).when(store).release(anyString(), anyString());

        run.close();

        assertThat(run.summary().status()).isEqualTo("INCOMPLETE");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
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
