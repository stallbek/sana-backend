package com.is.aigul.service;

import com.is.aigul.config.WalletWalletProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WalletPassRateLimitService {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final WalletWalletProperties properties;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public WalletPassRateLimitService(WalletWalletProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision attempt(String clientIp) {
        Instant now = clock.instant();
        Bucket bucket = buckets.computeIfAbsent(clientIp, ignored -> new Bucket());
        synchronized (bucket) {
            prune(bucket, now);
            bucket.lastActivity = now;
            if (bucket.attempts.size() >= properties.getAttemptsPerHour()) {
                Instant first = bucket.attempts.peekFirst();
                return Decision.rejection(retryAfterSeconds(first, now));
            }
            bucket.attempts.addLast(now);
            return Decision.allowance();
        }
    }

    @Scheduled(fixedDelayString = "${walletwallet.cleanup-interval-ms:300000}")
    public void cleanupInactiveBuckets() {
        Instant now = clock.instant();
        buckets.forEach((clientIp, bucket) -> {
            synchronized (bucket) {
                prune(bucket, now);
                if (bucket.attempts.isEmpty() && !bucket.lastActivity.isAfter(now.minus(WINDOW))) {
                    buckets.remove(clientIp, bucket);
                }
            }
        });
    }

    int trackedBucketCount() {
        return buckets.size();
    }

    private void prune(Bucket bucket, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!bucket.attempts.isEmpty() && !bucket.attempts.peekFirst().isAfter(cutoff)) {
            bucket.attempts.removeFirst();
        }
    }

    private long retryAfterSeconds(Instant firstAttempt, Instant now) {
        long millis = Duration.between(now, firstAttempt.plus(WINDOW)).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        private static Decision allowance() {
            return new Decision(true, 0);
        }

        private static Decision rejection(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private static final class Bucket {
        private final Deque<Instant> attempts = new ArrayDeque<>();
        private Instant lastActivity = Instant.EPOCH;
    }
}
