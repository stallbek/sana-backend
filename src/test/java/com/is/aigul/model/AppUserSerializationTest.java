package com.is.aigul.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppUserSerializationTest {

    @Test
    void passwordHashIsNeverSerialized() throws Exception {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setUsername("stalbek");
        user.setPasswordHash("{argon2}$argon2id$example");
        user.setEmail("private@example.com");
        user.setPhone("+77011234567");
        user.setRole("USER");

        String json = new ObjectMapper().writeValueAsString(user);

        assertThat(json)
                .contains("\"username\":\"stalbek\"")
                .doesNotContain("password")
                .doesNotContain("argon2")
                .doesNotContain("private@example.com")
                .doesNotContain("+77011234567");
    }
}
