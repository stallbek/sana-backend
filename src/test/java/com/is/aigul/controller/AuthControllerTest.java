package com.is.aigul.controller;

import com.is.aigul.exception.RegistrationException;
import com.is.aigul.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static com.is.aigul.exception.RegistrationException.Reason.IDENTIFIER_ALREADY_REGISTERED;
import static com.is.aigul.exception.RegistrationException.Reason.INVALID_REGISTRATION;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void registerReturnsCreatedToken() throws Exception {
        when(authService.register("user@example.com", "exact password"))
                .thenReturn("new-token");

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("""
                                {
                                  "identifier": "user@example.com",
                                  "password": "exact password"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("new-token"));
    }

    @Test
    void registerReturnsStableValidationAndDuplicateErrors() throws Exception {
        when(authService.register("bad", "short"))
                .thenThrow(new RegistrationException(
                        INVALID_REGISTRATION,
                        "A valid email or phone is required"
                ));
        when(authService.register("user@example.com", "exact password"))
                .thenThrow(new RegistrationException(
                        IDENTIFIER_ALREADY_REGISTERED,
                        "This email or phone is already registered"
                ));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"identifier\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"identifier":"user@example.com","password":"exact password"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("IDENTIFIER_ALREADY_REGISTERED"));
    }

    @Test
    void loginAcceptsPreferredIdentifierAndLegacyUsername() throws Exception {
        when(authService.login(null, "user@example.com", "password"))
                .thenReturn("email-token");
        when(authService.login("legacy", null, "password"))
                .thenReturn("legacy-token");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"identifier":"user@example.com","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("email-token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"legacy\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("legacy-token"));

        verify(authService).login(null, "user@example.com", "password");
        verify(authService).login("legacy", null, "password");
    }

    @Test
    void invalidOrConflictingLoginKeepsGenericUnauthorizedResponse() throws Exception {
        when(authService.login("legacy", "other@example.com", "password"))
                .thenReturn(null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "username":"legacy",
                                  "identifier":"other@example.com",
                                  "password":"password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid credentials"));
    }
}
