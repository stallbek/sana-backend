package com.is.aigul.service;

import com.is.aigul.config.PasswordConfiguration;
import com.is.aigul.model.AppUser;
import com.is.aigul.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_ID = 17L;
    private static final String USERNAME = "stalbek";
    private static final String PASSWORD = "  exact password \uD83D\uDD10  ";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordConfiguration.createPasswordEncoder();
        authService = new AuthService(userRepository, passwordEncoder);
    }

    @Test
    void loginMatchesTheHashAndCreatesTheExistingSessionToken() {
        AppUser user = user(passwordEncoder.encode(PASSWORD));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        String token = authService.login(USERNAME, PASSWORD);

        assertThat(token).isNotBlank();
        assertThat(authService.getUserId(token)).isEqualTo(USER_ID);
    }

    @Test
    void identifierLoginNormalizesEmailAfterCheckingExactLegacyUsername() {
        AppUser user = user(passwordEncoder.encode(PASSWORD));
        user.setEmail("user@example.com");
        when(userRepository.findByUsername("  USER@EXAMPLE.COM "))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));

        String token = authService.login(null, "  USER@EXAMPLE.COM ", PASSWORD);

        assertThat(authService.getUserId(token)).isEqualTo(USER_ID);
    }

    @Test
    void identifierLoginPrefersAnExactLegacyUsernameBeforeContactLookup() {
        AppUser user = user(passwordEncoder.encode(PASSWORD));
        when(userRepository.findByUsername("legacy@example.com"))
                .thenReturn(Optional.of(user));

        assertThat(authService.login(null, "legacy@example.com", PASSWORD)).isNotBlank();
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void identifierLoginNormalizesFormattedInternationalPhone() {
        AppUser user = user(passwordEncoder.encode(PASSWORD));
        user.setPhone("+77011234567");
        when(userRepository.findByUsername("+7 (701) 123-45-67"))
                .thenReturn(Optional.empty());
        when(userRepository.findByPhone("+77011234567"))
                .thenReturn(Optional.of(user));

        String token = authService.login(null, "+7 (701) 123-45-67", PASSWORD);

        assertThat(authService.getUserId(token)).isEqualTo(USER_ID);
    }

    @Test
    void loginRejectsConflictingUsernameAndIdentifier() {
        assertThat(authService.login(USERNAME, "other@example.com", PASSWORD)).isNull();

        verifyNoInteractions(userRepository);
    }

    @Test
    void registerNormalizesEmailHashesTheExactPasswordAndCreatesSession() {
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(USER_ID);
            return saved;
        });

        String token = authService.register("  User@Example.COM  ", PASSWORD);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getUsername()).isNull();
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getName()).isNull();
        assertThat(saved.getDefaultLocation()).isNull();
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(authService.getUserId(token)).isEqualTo(USER_ID);
    }

    @Test
    void registerNormalizesFormattedInternationalPhone() {
        when(userRepository.existsByPhone("+77011234567")).thenReturn(false);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(USER_ID);
            return saved;
        });

        authService.register(" +7 (701) 123-45-67 ", "password");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo("+77011234567");
        assertThat(captor.getValue().getEmail()).isNull();
    }

    @Test
    void registerCountsUnicodeCodePointsAndPreservesBoundaryPasswords() {
        String eightEmoji = "\uD83D\uDD10".repeat(8);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(USER_ID);
            return saved;
        });

        authService.register("user@example.com", eightEmoji);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(passwordEncoder.matches(eightEmoji, captor.getValue().getPasswordHash()))
                .isTrue();
    }

    @Test
    void registerAcceptsTheMaximumPasswordLength() {
        String maximumPassword = "x".repeat(128);
        when(userRepository.existsByPhone("+77011234567")).thenReturn(false);
        when(userRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            saved.setId(USER_ID);
            return saved;
        });

        authService.register("+77011234567", maximumPassword);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(passwordEncoder.matches(
                maximumPassword,
                captor.getValue().getPasswordHash()
        )).isTrue();
    }

    @Test
    void registerRejectsMalformedIdentifiersAndInvalidPasswordBoundaries() {
        assertInvalidRegistration("not-an-identifier", "password");
        assertInvalidRegistration("user@example", "password");
        assertInvalidRegistration("+1 23 letters", "password");
        assertInvalidRegistration("+1234567", "password");
        assertInvalidRegistration("+1234567890123456", "password");
        assertInvalidRegistration("user@example.com", "short");
        assertInvalidRegistration("user@example.com", " ".repeat(8));
        assertInvalidRegistration("user@example.com", "\u00A0".repeat(8));
        assertInvalidRegistration("user@example.com", "x".repeat(129));
    }

    @Test
    void registerRejectsKnownAndConcurrentDuplicates() {
        when(userRepository.existsByEmailIgnoreCase("known@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("known@example.com", "password"))
                .isInstanceOf(com.is.aigul.exception.RegistrationException.class)
                .extracting("reason")
                .isEqualTo(com.is.aigul.exception.RegistrationException.Reason
                        .IDENTIFIER_ALREADY_REGISTERED);

        when(userRepository.existsByPhone("+77011234567")).thenReturn(false);
        when(userRepository.saveAndFlush(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("unique index"));

        assertThatThrownBy(() -> authService.register("+7 701 123 45 67", "password"))
                .isInstanceOf(com.is.aigul.exception.RegistrationException.class)
                .extracting("reason")
                .isEqualTo(com.is.aigul.exception.RegistrationException.Reason
                        .IDENTIFIER_ALREADY_REGISTERED);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userRepository.findByUsername(USERNAME))
                .thenReturn(Optional.of(user(passwordEncoder.encode(PASSWORD))));

        assertThat(authService.login(USERNAME, "wrong password")).isNull();
    }

    @Test
    void loginFailsClosedForPlaintextOrMalformedStoredCredentials() {
        when(userRepository.findByUsername(USERNAME))
                .thenReturn(
                        Optional.of(user(PASSWORD)),
                        Optional.of(user("{argon2}$argon2id$broken"))
                );

        assertThat(authService.login(USERNAME, PASSWORD)).isNull();
        assertThat(authService.login(USERNAME, PASSWORD)).isNull();
    }

    @Test
    void loginRejectsMissingCredentialsBeforeQueryingTheDatabase() {
        assertThat(authService.login(null, PASSWORD)).isNull();
        assertThat(authService.login(" ", PASSWORD)).isNull();
        assertThat(authService.login(USERNAME, null)).isNull();
        assertThat(authService.login(USERNAME, "")).isNull();

        verifyNoInteractions(userRepository);
    }

    private AppUser user(String passwordHash) {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setPasswordHash(passwordHash);
        user.setRole("USER");
        return user;
    }

    private void assertInvalidRegistration(String identifier, String password) {
        assertThatThrownBy(() -> authService.register(identifier, password))
                .isInstanceOf(com.is.aigul.exception.RegistrationException.class)
                .extracting("reason")
                .isEqualTo(com.is.aigul.exception.RegistrationException.Reason
                        .INVALID_REGISTRATION);
    }
}
