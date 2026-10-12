package com.is.aigul.service;

import com.is.aigul.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthRateLimitServiceTest {
    @Test void limitsLoginAndRegistrationIndependentlyAndRecoversAtWindowBoundary() {
        MutableClock clock = new MutableClock(); AuthRateLimitService limits = new AuthRateLimitService(clock);
        for (int i = 0; i < 10; i++) assertThat(limits.retryAfter("127.0.0.1", false)).isZero();
        assertThat(limits.retryAfter("127.0.0.1", false)).isEqualTo(60);
        for (int i = 0; i < 5; i++) assertThat(limits.retryAfter("127.0.0.1", true)).isZero();
        assertThat(limits.retryAfter("127.0.0.1", true)).isEqualTo(3600);
        clock.advance(Duration.ofMinutes(1)); assertThat(limits.retryAfter("127.0.0.1", false)).isZero();
        assertThat(limits.retryAfter("127.0.0.1", true)).isEqualTo(3540);
        clock.advance(Duration.ofHours(1)); limits.cleanup(); assertThat(limits.retryAfter("127.0.0.1", true)).isZero();
    }
}
