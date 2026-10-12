package com.is.aigul.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** A bounded, single-instance limiter. Forwarded headers never define its keys. */
@Service
public class AuthRateLimitService {
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private static final int MAX_BUCKETS = 10_000;

    public AuthRateLimitService(Clock clock) { this.clock = clock; }

    public synchronized long retryAfter(String address, boolean registration) {
        Instant now = clock.instant();
        Duration window = registration ? Duration.ofHours(1) : Duration.ofMinutes(1);
        int limit = registration ? 5 : 10;
        String key = (registration ? "register:" : "login:") + address;
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            cleanup();
            if (buckets.size() >= MAX_BUCKETS) return 60;
            bucket = new Bucket(window);
            buckets.put(key, bucket);
        }
        bucket.prune(now);
        if (bucket.attempts.size() >= limit) {
            long remaining = Duration.between(now, bucket.attempts.getFirst().plus(window)).toMillis();
            return Math.max(1, (remaining + 999) / 1000);
        }
        bucket.attempts.addLast(now);
        return 0;
    }

    @Scheduled(fixedDelay = 300_000)
    public synchronized void cleanup() {
        Instant now = clock.instant();
        buckets.values().removeIf(bucket -> {
            bucket.prune(now);
            return bucket.attempts.isEmpty();
        });
    }

    private static final class Bucket {
        private final Duration window;
        private final ArrayDeque<Instant> attempts = new ArrayDeque<>();
        private Bucket(Duration window) { this.window = window; }
        private void prune(Instant now) {
            while (!attempts.isEmpty() && !attempts.getFirst().isAfter(now.minus(window))) attempts.removeFirst();
        }
    }
}
