package com.is.aigul.service;

import com.is.aigul.config.WalletWalletProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WalletPassRateLimitServiceTest {

    @Test
    void usesSeparateRollingBucketsAndAllowsAgainAfterAnHour() {
        WalletWalletProperties properties = new WalletWalletProperties();
        properties.setAttemptsPerHour(2);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        WalletPassRateLimitService limiter = new WalletPassRateLimitService(properties, clock);

        assertThat(limiter.attempt("203.0.113.1").allowed()).isTrue();
        assertThat(limiter.attempt("203.0.113.1").allowed()).isTrue();
        WalletPassRateLimitService.Decision rejected = limiter.attempt("203.0.113.1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(3600);
        assertThat(limiter.attempt("203.0.113.2").allowed()).isTrue();

        clock.set(Instant.parse("2026-08-06T01:00:00Z"));
        assertThat(limiter.attempt("203.0.113.1").allowed()).isTrue();
    }

    @Test
    void expiresInactiveBucketsDuringCleanup() {
        WalletWalletProperties properties = new WalletWalletProperties();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        WalletPassRateLimitService limiter = new WalletPassRateLimitService(properties, clock);
        limiter.attempt("203.0.113.1");

        clock.set(Instant.parse("2026-08-06T01:00:01Z"));
        limiter.cleanupInactiveBuckets();

        assertThat(limiter.trackedBucketCount()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
