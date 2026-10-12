package com.is.aigul.service;

import com.is.aigul.config.AiLimitsProperties;
import com.is.aigul.exception.AiRateLimitException;
import com.is.aigul.exception.AiRateLimitException.LimitType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

@Service
public class AiUsageLimitService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageLimitService.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final AiLimitsProperties properties;
    private final Clock clock;
    private final Map<Long, UserState> users = new ConcurrentHashMap<>();

    public AiUsageLimitService(AiLimitsProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Permit reserve(Long userId, AiOperation operation) {
        if (userId == null) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
        if (properties.getMode() == AiLimitsProperties.Mode.DISABLED) {
            return Permit.NOOP;
        }

        while (true) {
            Instant now = clock.instant();
            UserState state = users.computeIfAbsent(userId, ignored -> new UserState(now));
            synchronized (state) {
                if (users.get(userId) != state) {
                    continue;
                }
                pruneExpired(state, now);
                state.lastActivity = now;

                Rejection rejection = findRejection(state, operation, now);
                if (rejection == null && state.activePermits >= properties.getConcurrentOperations()) {
                    rejection = new Rejection(properties.getConcurrentRetrySeconds(), LimitType.CONCURRENT);
                }

                if (properties.getMode() == AiLimitsProperties.Mode.ENFORCE && rejection != null) {
                    throw new AiRateLimitException(rejection.retryAfterSeconds(), rejection.limitType());
                }

                state.events.addLast(new UsageEvent(now, operation));
                state.activePermits++;
                if (properties.getMode() == AiLimitsProperties.Mode.OBSERVE) {
                    log.info("AI limits observation operation={} rollingOperations={} rollingUnits={} activeOperations={} hypotheticalLimitType={}",
                            operation, countSince(state, now.minus(MINUTE), event -> true),
                            unitsSince(state, now.minus(MINUTE)), state.activePermits,
                            rejection == null ? "NONE" : rejection.limitType());
                }
                return new Permit(() -> release(state));
            }
        }
    }

    public void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AI text is required.");
        }
        if (text.length() > properties.getTextMaxCharacters()) {
            throw new IllegalArgumentException("AI text must not exceed " + properties.getTextMaxCharacters() + " characters.");
        }
    }

    public void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required.");
        }
        if (file.getSize() > properties.getUploadMaxBytes()) {
            throw new IllegalArgumentException("AI audio upload must not exceed " + properties.getUploadMaxBytes() + " bytes.");
        }
    }

    @Scheduled(fixedDelayString = "${ai.limits.cleanup-interval-ms:300000}")
    public void cleanupInactiveStates() {
        if (properties.getMode() == AiLimitsProperties.Mode.DISABLED) {
            users.clear();
            return;
        }
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(properties.getCleanupAfterMinutes()));
        users.forEach((userId, state) -> {
            synchronized (state) {
                if (state.activePermits == 0 && !state.lastActivity.isAfter(cutoff)) {
                    users.remove(userId, state);
                }
            }
        });
    }

    int trackedUserCount() {
        return users.size();
    }

    private Rejection findRejection(UserState state, AiOperation operation, Instant now) {
        List<Rejection> rejections = new ArrayList<>();
        addCountRejection(rejections, state, operation, now, MINUTE,
                event -> event.operation().isPrimarySubmission(), properties.getPrimarySubmissionsPerMinute(),
                operation.isPrimarySubmission(), LimitType.BURST);
        addCountRejection(rejections, state, operation, now, MINUTE,
                event -> true, properties.getOperationsPerMinute(), true, LimitType.BURST);
        addUnitRejection(rejections, state, operation, now, MINUTE, properties.getUnitsPerMinute(), LimitType.BURST);
        addUnitRejection(rejections, state, operation, now, HOUR, properties.getUnitsPerHour(), LimitType.HOURLY);
        addCountRejection(rejections, state, operation, now, HOUR,
                event -> event.operation().isRealtimeToken(), properties.getRealtimeTokensPerHour(),
                operation.isRealtimeToken(), LimitType.REALTIME_TOKENS);
        return rejections.stream().max(java.util.Comparator.comparingLong(Rejection::retryAfterSeconds)).orElse(null);
    }

    private void addCountRejection(List<Rejection> rejections, UserState state, AiOperation operation, Instant now,
            Duration window, Predicate<UsageEvent> filter, int limit, boolean included, LimitType type) {
        if (!included) {
            return;
        }
        List<UsageEvent> matching = eventsSince(state, now.minus(window), filter);
        int excess = matching.size() + 1 - limit;
        if (excess > 0) {
            UsageEvent blocking = matching.get(excess - 1);
            rejections.add(new Rejection(retrySeconds(blocking.at(), window, now), type));
        }
    }

    private void addUnitRejection(List<Rejection> rejections, UserState state, AiOperation operation, Instant now,
            Duration window, int limit, LimitType type) {
        List<UsageEvent> matching = eventsSince(state, now.minus(window), event -> true);
        int currentUnits = matching.stream().mapToInt(event -> event.operation().units()).sum();
        int unitsToExpire = currentUnits + operation.units() - limit;
        if (unitsToExpire <= 0) {
            return;
        }
        int expiredUnits = 0;
        for (UsageEvent event : matching) {
            expiredUnits += event.operation().units();
            if (expiredUnits >= unitsToExpire) {
                rejections.add(new Rejection(retrySeconds(event.at(), window, now), type));
                return;
            }
        }
    }

    private List<UsageEvent> eventsSince(UserState state, Instant cutoff, Predicate<UsageEvent> filter) {
        return state.events.stream().filter(event -> event.at().isAfter(cutoff)).filter(filter).toList();
    }

    private long countSince(UserState state, Instant cutoff, Predicate<UsageEvent> filter) {
        return state.events.stream().filter(event -> event.at().isAfter(cutoff)).filter(filter).count();
    }

    private int unitsSince(UserState state, Instant cutoff) {
        return state.events.stream().filter(event -> event.at().isAfter(cutoff))
                .mapToInt(event -> event.operation().units()).sum();
    }

    private long retrySeconds(Instant eventAt, Duration window, Instant now) {
        long millis = Duration.between(now, eventAt.plus(window)).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private void release(UserState state) {
        synchronized (state) {
            state.activePermits--;
            state.lastActivity = clock.instant();
        }
    }

    private void pruneExpired(UserState state, Instant now) {
        Instant cutoff = now.minus(HOUR);
        while (!state.events.isEmpty() && !state.events.peekFirst().at().isAfter(cutoff)) {
            state.events.removeFirst();
        }
    }

    private record UsageEvent(Instant at, AiOperation operation) {}
    private record Rejection(long retryAfterSeconds, LimitType limitType) {}

    private static final class UserState {
        private final Deque<UsageEvent> events = new ArrayDeque<>();
        private int activePermits;
        private Instant lastActivity;

        private UserState(Instant now) {
            this.lastActivity = now;
        }
    }

    public static final class Permit implements AutoCloseable {
        private static final Permit NOOP = new Permit(() -> {});
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
