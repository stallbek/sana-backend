package com.is.aigul.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordConfigurationTest {

    private final PasswordEncoder passwordEncoder =
            PasswordConfiguration.createPasswordEncoder();

    @Test
    void encodesWithTaggedSaltedArgon2id() {
        String rawPassword = "  correct horse battery staple \uD83D\uDD10  ";

        String firstHash = passwordEncoder.encode(rawPassword);
        String secondHash = passwordEncoder.encode(rawPassword);

        assertThat(firstHash)
                .startsWith(PasswordConfiguration.ENCODED_PASSWORD_PREFIX + "$argon2id$")
                .doesNotContain(rawPassword);
        assertThat(PasswordConfiguration.isCurrentPasswordHash(firstHash)).isTrue();
        assertThat(passwordEncoder.matches(rawPassword, firstHash)).isTrue();
        assertThat(passwordEncoder.matches("wrong password", firstHash)).isFalse();
        assertThat(secondHash).isNotEqualTo(firstHash);
    }

    @Test
    void recognizesOnlyTheConfiguredHashFormat() {
        assertThat(PasswordConfiguration.isCurrentPasswordHash(null)).isFalse();
        assertThat(PasswordConfiguration.isCurrentPasswordHash("plaintext")).isFalse();
        assertThat(PasswordConfiguration.isCurrentPasswordHash(
                "{argon2}$argon2id$broken"
        )).isFalse();
    }
}
