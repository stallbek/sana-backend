package com.is.aigul.service;

import com.is.aigul.config.PasswordConfiguration;
import com.is.aigul.exception.RegistrationException;
import com.is.aigul.model.AppUser;
import com.is.aigul.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.is.aigul.exception.RegistrationException.Reason.IDENTIFIER_ALREADY_REGISTERED;
import static com.is.aigul.exception.RegistrationException.Reason.INVALID_REGISTRATION;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
    );
    private static final Pattern PHONE_INPUT_PATTERN = Pattern.compile(
            "^\\+[0-9\\s().-]+$"
    );

    private final Map<String, Session> userSessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private static final Duration SESSION_LIFETIME = Duration.ofHours(24);
    private static final int MAX_SESSIONS = 10_000;

    @Autowired
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, Clock.systemUTC());
    }

    private record Session(Long userId, Instant expiresAt) { }


    public String login(String username, String password) {
        return login(username, null, password);
    }

    public String login(String username, String identifier, String password) {
        if (password == null || password.isEmpty() || password.length() > 256
                || conflictingIdentifiers(username, identifier)) {
            return null;
        }

        String suppliedIdentifier = preferredIdentifier(username, identifier);
        if (suppliedIdentifier == null || suppliedIdentifier.isBlank() || suppliedIdentifier.length() > 512) {
            return null;
        }

        Optional<AppUser> userOpt = userRepository.findByUsername(suppliedIdentifier);
        if (userOpt.isEmpty()) {
            userOpt = findByContactIdentifier(suppliedIdentifier);
        }
        if (userOpt.isEmpty()) {
            return null;
        }

        AppUser user = userOpt.get();
        if (!passwordMatches(password, user.getPasswordHash())) {
            return null;
        }

        return createSession(user.getId());
    }

    @Transactional
    public String register(String identifier, String password) {
        ContactIdentifier normalizedIdentifier = normalizeRegistrationIdentifier(identifier);
        validateRegistrationPassword(password);

        if (identifierExists(normalizedIdentifier)) {
            throw new RegistrationException(
                    IDENTIFIER_ALREADY_REGISTERED,
                    "This email or phone is already registered"
            );
        }

        AppUser user = new AppUser();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("USER");
        if (normalizedIdentifier.email()) {
            user.setEmail(normalizedIdentifier.value());
        } else {
            user.setPhone(normalizedIdentifier.value());
        }

        try {
            AppUser savedUser = userRepository.saveAndFlush(user);
            return createSession(savedUser.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new RegistrationException(
                    IDENTIFIER_ALREADY_REGISTERED,
                    "This email or phone is already registered"
            );
        }
    }

    private boolean passwordMatches(String rawPassword, String storedPasswordHash) {
        if (!PasswordConfiguration.isCurrentPasswordHash(storedPasswordHash)) {
            return false;
        }
        try {
            return passwordEncoder.matches(rawPassword, storedPasswordHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Optional<AppUser> findByContactIdentifier(String identifier) {
        try {
            ContactIdentifier normalized = normalizeIdentifier(identifier);
            return normalized.email()
                    ? userRepository.findByEmailIgnoreCase(normalized.value())
                    : userRepository.findByPhone(normalized.value());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean identifierExists(ContactIdentifier identifier) {
        return identifier.email()
                ? userRepository.existsByEmailIgnoreCase(identifier.value())
                : userRepository.existsByPhone(identifier.value());
    }

    private ContactIdentifier normalizeRegistrationIdentifier(String identifier) {
        try {
            return normalizeIdentifier(identifier);
        } catch (IllegalArgumentException exception) {
            throw new RegistrationException(INVALID_REGISTRATION, exception.getMessage());
        }
    }

    private ContactIdentifier normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 512) {
            throw new IllegalArgumentException("A valid email or phone is required");
        }

        String trimmed = identifier.strip();
        if (trimmed.contains("@")) {
            String email = trimmed.toLowerCase(Locale.ROOT);
            if (email.codePointCount(0, email.length()) > 254
                    || !EMAIL_PATTERN.matcher(email).matches()) {
                throw new IllegalArgumentException("A valid email or phone is required");
            }
            return new ContactIdentifier(email, true);
        }

        if (!PHONE_INPUT_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("A valid email or phone is required");
        }
        String phone = "+" + trimmed.substring(1).replaceAll("[\\s().-]", "");
        int digitCount = phone.length() - 1;
        if (digitCount < 8 || digitCount > 15) {
            throw new IllegalArgumentException("A valid email or phone is required");
        }
        return new ContactIdentifier(phone, false);
    }

    private void validateRegistrationPassword(String password) {
        if (password == null || password.codePoints().allMatch(
                codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint)
        )) {
            throw new RegistrationException(
                    INVALID_REGISTRATION,
                    "Password must contain between 8 and 128 characters"
            );
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
            throw new RegistrationException(
                    INVALID_REGISTRATION,
                    "Password must contain between 8 and 128 characters"
            );
        }
    }

    private boolean conflictingIdentifiers(String username, String identifier) {
        return username != null
                && identifier != null
                && !username.equals(identifier);
    }

    private String preferredIdentifier(String username, String identifier) {
        return identifier != null ? identifier : username;
    }

    private synchronized String createSession(Long userId) {
        cleanupExpiredSessions();
        if (userSessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("Session capacity reached");
        }
        String token = UUID.randomUUID().toString();
        userSessions.put(token, new Session(userId, clock.instant().plus(SESSION_LIFETIME)));
        return token;
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredSessions() {
        Instant now = clock.instant();
        userSessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record ContactIdentifier(String value, boolean email) {
    }

    public void logout(String token) {
        if (token != null) {
            userSessions.remove(token);
        }
    }

    public Long getUserId(String token) {
        if (token == null || token.length() > 64) return null;
        Session session = userSessions.get(token);
        if (session == null) return null;
        if (!session.expiresAt().isAfter(clock.instant())) {
            userSessions.remove(token, session);
            return null;
        }
        return session.userId();
    }
}
