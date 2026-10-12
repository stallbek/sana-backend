package com.is.aigul.service;

import com.is.aigul.config.PasswordConfiguration;
import com.is.aigul.model.AppUser;
import com.is.aigul.repository.UserRepository;
import com.is.aigul.support.MutableClock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionTest {
    @Test void sessionExpiresAt24HoursAndLogoutRevokesItImmediately() {
        UserRepository users = mock(UserRepository.class);
        var encoder = PasswordConfiguration.createPasswordEncoder();
        MutableClock clock = new MutableClock();
        AuthService auth = new AuthService(users, encoder, clock);
        AppUser user = new AppUser(); user.setId(7L); user.setPasswordHash(encoder.encode("test-password"));
        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        String first = auth.login("demo", "test-password");
        assertThat(auth.getUserId(first)).isEqualTo(7L);
        auth.logout(first); assertThat(auth.getUserId(first)).isNull();
        String second = auth.login("demo", "test-password");
        clock.advance(Duration.ofHours(24).minusSeconds(1)); assertThat(auth.getUserId(second)).isEqualTo(7L);
        clock.advance(Duration.ofSeconds(1)); assertThat(auth.getUserId(second)).isNull();
        auth.cleanupExpiredSessions(); assertThat(auth.getUserId(second)).isNull();
    }
    @Test void oversizedLoginInputIsRejectedBeforeRepositoryWork() {
        UserRepository users = mock(UserRepository.class);
        AuthService auth = new AuthService(users, mock(org.springframework.security.crypto.password.PasswordEncoder.class));
        assertThat(auth.login("x".repeat(513), "password")).isNull();
        assertThat(auth.login("demo", "x".repeat(257))).isNull();
        verifyNoInteractions(users);
    }
}
