package com.is.aigul.service;

import com.is.aigul.config.AiLimitsProperties;
import com.is.aigul.exception.AiRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUsageLimitServiceTest {

    @Test
    void rollingMinuteBoundaryReturnsExactRetryAndThenExpires() {
        TestContext context = context();
        context.properties.setPrimarySubmissionsPerMinute(2);

        reserveAndClose(context.service, 7L, AiOperation.TEXT_COMMAND);
        context.clock.advanceSeconds(10);
        reserveAndClose(context.service, 7L, AiOperation.TEXT_COMMAND);
        context.clock.advanceSeconds(20);

        assertThatThrownBy(() -> context.service.reserve(7L, AiOperation.TEXT_COMMAND))
                .isInstanceOfSatisfying(AiRateLimitException.class, exception -> {
                    assertThat(exception.getLimitType()).isEqualTo(AiRateLimitException.LimitType.BURST);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(30);
                });

        context.clock.advanceSeconds(30);
        reserveAndClose(context.service, 7L, AiOperation.TEXT_COMMAND);
    }

    @Test
    void weightedMinuteAndHourCostsUseCompleteOperationCost() {
        TestContext minute = context();
        minute.properties.setUnitsPerMinute(3);
        reserveAndClose(minute.service, 7L, AiOperation.FULL_AUDIO_COMMAND);
        reserveAndClose(minute.service, 7L, AiOperation.TEXT_COMMAND);
        assertRateLimited(minute.service, 7L, AiOperation.TEXT_COMMAND, AiRateLimitException.LimitType.BURST, 60);

        TestContext hour = context();
        hour.properties.setUnitsPerHour(3);
        reserveAndClose(hour.service, 7L, AiOperation.FULL_AUDIO_COMMAND);
        reserveAndClose(hour.service, 7L, AiOperation.TEXT_COMMAND);
        assertRateLimited(hour.service, 7L, AiOperation.TEXT_COMMAND, AiRateLimitException.LimitType.HOURLY, 3600);
    }

    @Test
    void totalOperationsAllowParsePlusGetUntilConfiguredLimit() {
        TestContext context = context();
        context.properties.setOperationsPerMinute(2);
        reserveAndClose(context.service, 7L, AiOperation.TEXT_COMMAND);
        reserveAndClose(context.service, 7L, AiOperation.GET_EXECUTION);

        assertRateLimited(context.service, 7L, AiOperation.GET_EXECUTION,
                AiRateLimitException.LimitType.BURST, 60);
    }

    @Test
    void fullVoiceThenGetUsesFourCombinedUnits() {
        TestContext context = context();
        context.properties.setUnitsPerMinute(3);
        reserveAndClose(context.service, 7L, AiOperation.FULL_AUDIO_COMMAND);

        assertRateLimited(context.service, 7L, AiOperation.GET_EXECUTION,
                AiRateLimitException.LimitType.BURST, 60);
    }

    @Test
    void concurrentPermitsRejectBrieflyAndReleaseIdempotently() {
        TestContext context = context();
        context.properties.setConcurrentOperations(2);
        AiUsageLimitService.Permit first = context.service.reserve(7L, AiOperation.TEXT_COMMAND);
        AiUsageLimitService.Permit second = context.service.reserve(7L, AiOperation.GET_EXECUTION);

        assertRateLimited(context.service, 7L, AiOperation.TEXT_COMMAND,
                AiRateLimitException.LimitType.CONCURRENT, 1);

        first.close();
        first.close();
        context.service.reserve(7L, AiOperation.TEXT_COMMAND).close();
        second.close();
    }

    @Test
    void usersAndRealtimeTokenQuotaAreIsolated() {
        TestContext context = context();
        context.properties.setRealtimeTokensPerHour(1);
        reserveAndClose(context.service, 7L, AiOperation.REALTIME_TOKEN);

        assertRateLimited(context.service, 7L, AiOperation.REALTIME_TOKEN,
                AiRateLimitException.LimitType.REALTIME_TOKENS, 3600);
        reserveAndClose(context.service, 8L, AiOperation.REALTIME_TOKEN);
    }

    @Test
    void cleanupRemovesOnlyInactiveOldStates() {
        TestContext context = context();
        AiUsageLimitService.Permit active = context.service.reserve(7L, AiOperation.TEXT_COMMAND);
        reserveAndClose(context.service, 8L, AiOperation.TEXT_COMMAND);
        context.clock.advanceSeconds(66 * 60L);

        context.service.cleanupInactiveStates();

        assertThat(context.service.trackedUserCount()).isEqualTo(1);
        active.close();
        context.clock.advanceSeconds(66 * 60L);
        context.service.cleanupInactiveStates();
        assertThat(context.service.trackedUserCount()).isZero();
    }

    @Test
    void disabledDoesNotTrackAndObserveRecordsWithoutRejecting() {
        TestContext disabled = context();
        disabled.properties.setMode(AiLimitsProperties.Mode.DISABLED);
        reserveAndClose(disabled.service, 7L, AiOperation.TEXT_COMMAND);
        assertThat(disabled.service.trackedUserCount()).isZero();

        TestContext observe = context();
        observe.properties.setMode(AiLimitsProperties.Mode.OBSERVE);
        observe.properties.setPrimarySubmissionsPerMinute(1);
        reserveAndClose(observe.service, 7L, AiOperation.TEXT_COMMAND);
        reserveAndClose(observe.service, 7L, AiOperation.TEXT_COMMAND);
        observe.properties.setMode(AiLimitsProperties.Mode.ENFORCE);
        assertThatThrownBy(() -> observe.service.reserve(7L, AiOperation.TEXT_COMMAND))
                .isInstanceOf(AiRateLimitException.class);
    }

    @Test
    void textAndUploadValidationUseConfiguredBoundaries() {
        TestContext context = context();
        context.properties.setTextMaxCharacters(4);
        context.properties.setUploadMaxBytes(2);

        context.service.validateText("1234");
        context.service.validateUpload(new MockMultipartFile("file", new byte[]{1, 2}));
        assertThatThrownBy(() -> context.service.validateText("12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context.service.validateUpload(new MockMultipartFile("file", new byte[]{1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservationsRemainThreadSafeUnderContention() throws Exception {
        TestContext context = context();
        context.properties.setPrimarySubmissionsPerMinute(100);
        context.properties.setOperationsPerMinute(100);
        context.properties.setUnitsPerMinute(100);
        context.properties.setConcurrentOperations(100);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    reserveAndClose(context.service, 7L, AiOperation.TEXT_COMMAND);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertRateLimited(context.service, 7L, AiOperation.TEXT_COMMAND,
                AiRateLimitException.LimitType.BURST, 60);
    }

    private void assertRateLimited(AiUsageLimitService service, Long userId, AiOperation operation,
            AiRateLimitException.LimitType type, long retrySeconds) {
        assertThatThrownBy(() -> service.reserve(userId, operation))
                .isInstanceOfSatisfying(AiRateLimitException.class, exception -> {
                    assertThat(exception.getLimitType()).isEqualTo(type);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(retrySeconds);
                });
    }

    private void reserveAndClose(AiUsageLimitService service, Long userId, AiOperation operation) {
        service.reserve(userId, operation).close();
    }

    private TestContext context() {
        AiLimitsProperties properties = new AiLimitsProperties();
        properties.setPrimarySubmissionsPerMinute(1_000);
        properties.setOperationsPerMinute(1_000);
        properties.setUnitsPerMinute(1_000);
        properties.setUnitsPerHour(10_000);
        properties.setRealtimeTokensPerHour(1_000);
        properties.setConcurrentOperations(100);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
        return new TestContext(properties, clock, new AiUsageLimitService(properties, clock));
    }

    private record TestContext(AiLimitsProperties properties, MutableClock clock, AiUsageLimitService service) {}

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
