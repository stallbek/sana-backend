package com.is.aigul.migration;

import com.is.aigul.config.PasswordConfiguration;
import db.migration.V5__hash_legacy_user_passwords;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyPasswordMigrationTest {

    private static final String LEGACY_PASSWORD = "legacy password \uD83D\uDD10";

    private String databaseUrl;
    private Connection connection;
    private Context context;

    @BeforeEach
    void setUp() throws Exception {
        String databaseName = "password_migration_"
                + UUID.randomUUID().toString().replace("-", "");
        databaseUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(
                databaseUrl,
                "sa",
                ""
        );
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        id BIGINT PRIMARY KEY,
                        username VARCHAR(255) NOT NULL,
                        password VARCHAR(255) NOT NULL,
                        role VARCHAR(255) NOT NULL
                    )
                    """);
        }
        context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
    }

    @Test
    void flywayDiscoversAndRunsVersionFiveFromBaselineFour() throws Exception {
        insertUser(1L, "first", LEGACY_PASSWORD);

        Flyway flyway = Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("4"))
                .target(MigrationVersion.fromVersion("5"))
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        String passwordHash = passwordsByUserId().get(1L);
        assertThat(flyway.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("5"));
        assertThat(PasswordConfiguration.isCurrentPasswordHash(passwordHash)).isTrue();
        assertThat(PasswordConfiguration.createPasswordEncoder()
                .matches(LEGACY_PASSWORD, passwordHash)).isTrue();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void convertsPlaintextPasswordsWithUniqueHashesAndIsIdempotent() throws Exception {
        insertUser(1L, "first", LEGACY_PASSWORD);
        insertUser(2L, "second", LEGACY_PASSWORD);

        V5__hash_legacy_user_passwords migration =
                new V5__hash_legacy_user_passwords();
        migration.migrate(context);
        Map<Long, String> firstRunHashes = passwordsByUserId();

        PasswordEncoder passwordEncoder = PasswordConfiguration.createPasswordEncoder();
        assertThat(firstRunHashes.values())
                .allMatch(PasswordConfiguration::isCurrentPasswordHash)
                .doesNotHaveDuplicates();
        assertThat(firstRunHashes.values())
                .allMatch(hash -> passwordEncoder.matches(LEGACY_PASSWORD, hash));

        migration.migrate(context);

        assertThat(passwordsByUserId()).isEqualTo(firstRunHashes);
    }

    @Test
    void rejectsBlankAndMalformedTaggedCredentials() throws Exception {
        insertUser(1L, "blank", " ");

        assertThatThrownBy(() -> new V5__hash_legacy_user_passwords().migrate(context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("blank password")
                .hasMessageContaining("user id 1");

        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM users");
        }
        insertUser(2L, "malformed", "{argon2}$argon2id$broken");

        assertThatThrownBy(() -> new V5__hash_legacy_user_passwords().migrate(context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("malformed Argon2 password hash")
                .hasMessageContaining("user id 2");
    }

    private void insertUser(long id, String username, String password) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (id, username, password, role)
                VALUES (?, ?, ?, 'USER')
                """)) {
            statement.setLong(1, id);
            statement.setString(2, username);
            statement.setString(3, password);
            statement.executeUpdate();
        }
    }

    private Map<Long, String> passwordsByUserId() throws Exception {
        Map<Long, String> passwords = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, password
                     FROM users
                     ORDER BY id
                     """)) {
            while (resultSet.next()) {
                passwords.put(resultSet.getLong("id"), resultSet.getString("password"));
            }
        }
        return passwords;
    }
}
