package com.is.aigul.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class PasswordConfiguration {

    public static final String ENCODER_ID = "argon2";
    public static final String ENCODED_PASSWORD_PREFIX = "{argon2}";

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final Pattern CURRENT_HASH_PATTERN = Pattern.compile(
            "^\\{argon2}\\$argon2id\\$v=19\\$m=19456,t=2,p=1"
                    + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$"
    );

    @Bean
    public PasswordEncoder passwordEncoder() {
        return createPasswordEncoder();
    }

    public static PasswordEncoder createPasswordEncoder() {
        Argon2PasswordEncoder argon2PasswordEncoder = new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS
        );
        return new DelegatingPasswordEncoder(
                ENCODER_ID,
                Map.of(ENCODER_ID, argon2PasswordEncoder)
        );
    }

    public static boolean isCurrentPasswordHash(String value) {
        return value != null && CURRENT_HASH_PATTERN.matcher(value).matches();
    }
}
