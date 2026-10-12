package com.is.aigul.migration;

import com.is.aigul.config.PasswordConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void cleanDatabaseAppliesAllMigrationsAndActiveProductIndex() throws Exception {
        String schema = schemaName();
        Flyway flyway = flyway(schema, false);

        flyway.migrate();

        assertThat(flyway.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("6"));
        assertIndexDefinitions(schema);
        assertLegacyProductUniquenessRemoved(schema);
        assertCustomerPaymentSchema(schema);
        assertRegistrationSchema(schema);
    }

    @Test
    void existingSchemaIsBaselinedAtOneBeforePerformanceIndexes() throws Exception {
        String schema = schemaName();
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/B1__baseline.sql")
            );
            statement.execute("""
                    ALTER TABLE product
                    RENAME CONSTRAINT uk_product_name TO legacy_random_product_name_unique
                    """);
            statement.execute("""
                    ALTER TABLE product
                    ADD CONSTRAINT uk_product_user_name UNIQUE (user_id, name)
                    """);
            statement.execute("CREATE INDEX idx_product_user_name ON product (user_id, name)");
            statement.execute("""
                    CREATE UNIQUE INDEX uk_product_user_lower_name
                    ON product (user_id, lower(name))
                    """);
        }

        Flyway flyway = flyway(schema, true);
        flyway.migrate();

        assertThat(flyway.info().all())
                .extracting(info -> info.getVersion() == null ? null : info.getVersion().getVersion())
                .contains("1", "2", "3", "4", "5", "6");
        assertIndexDefinitions(schema);
        assertLegacyProductUniquenessRemoved(schema);
        assertCustomerPaymentSchema(schema);
        assertRegistrationSchema(schema);
    }

    @Test
    void plaintextPasswordsAreHashedByVersionFive() throws Exception {
        String schema = schemaName();
        flyway(schema, false, MigrationVersion.fromVersion("4")).migrate();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    INSERT INTO users (username, password, role)
                    VALUES
                        ('first', 'legacy password', 'USER'),
                        ('second', 'legacy password', 'USER')
                    """);
        }

        Flyway flyway = flyway(schema, false);
        flyway.migrate();

        Map<String, String> passwordHashes = new HashMap<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT username, password
                    FROM users
                    ORDER BY username
                    """)) {
                while (resultSet.next()) {
                    passwordHashes.put(
                            resultSet.getString("username"),
                            resultSet.getString("password")
                    );
                }
            }
        }

        PasswordEncoder passwordEncoder = PasswordConfiguration.createPasswordEncoder();
        assertThat(passwordHashes.values())
                .allMatch(PasswordConfiguration::isCurrentPasswordHash)
                .doesNotHaveDuplicates()
                .allMatch(hash -> passwordEncoder.matches("legacy password", hash));
        assertThat(flyway.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("6"));
    }

    @Test
    void versionSixPreservesLegacyUsersAndEnforcesNormalizedContactUniqueness()
            throws Exception {
        String schema = schemaName();
        flyway(schema, false, MigrationVersion.fromVersion("5")).migrate();

        String originalPasswordHash = PasswordConfiguration.createPasswordEncoder()
                .encode("legacy password");
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO %s.users (username, password, role, name)
                     VALUES (?, ?, 'USER', 'Legacy Name')
                     """.formatted(schema))) {
            statement.setString(1, "legacy-user");
            statement.setString(2, originalPasswordHash);
            statement.executeUpdate();
        }

        Flyway flyway = flyway(schema, false);
        flyway.migrate();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT username, password, role, name, email, phone
                    FROM %s.users
                    WHERE username = 'legacy-user'
                    """.formatted(schema))) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("username")).isEqualTo("legacy-user");
                assertThat(resultSet.getString("password")).isEqualTo(originalPasswordHash);
                assertThat(resultSet.getString("role")).isEqualTo("USER");
                assertThat(resultSet.getString("name")).isEqualTo("Legacy Name");
                assertThat(resultSet.getString("email")).isNull();
                assertThat(resultSet.getString("phone")).isNull();
            }

            statement.execute("""
                    INSERT INTO %s.users (email, password, role)
                    VALUES ('user@example.com', 'hash', 'USER')
                    """.formatted(schema));
            statement.execute("""
                    INSERT INTO %s.users (phone, password, role)
                    VALUES ('+77011234567', 'hash', 'USER')
                    """.formatted(schema));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO %s.users (email, password, role)
                    VALUES ('USER@EXAMPLE.COM', 'hash', 'USER')
                    """.formatted(schema)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO %s.users (phone, password, role)
                    VALUES ('+77011234567', 'hash', 'USER')
                    """.formatted(schema)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void activeNamesAreCaseInsensitivePerUserAndArchivedNamesCanBeRecreated() throws Exception {
        String schema = schemaName();
        flyway(schema, false).migrate();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            long archivedProductId;
            try (ResultSet resultSet = statement.executeQuery("""
                    INSERT INTO product (user_id, name, price)
                    VALUES (7, 'Atlas', 10)
                    RETURNING id
                    """)) {
                resultSet.next();
                archivedProductId = resultSet.getLong(1);
            }

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO product (user_id, name, price)
                    VALUES (7, 'aTLaS', 20)
                    """))
                    .isInstanceOf(SQLException.class);

            statement.execute("""
                    INSERT INTO product (user_id, name, price)
                    VALUES (8, 'ATLAS', 30)
                    """);
            statement.execute("""
                    UPDATE product
                    SET deleted_at = CURRENT_TIMESTAMP
                    WHERE id = %d
                    """.formatted(archivedProductId));
            statement.execute("""
                    INSERT INTO product (user_id, name, price)
                    VALUES (7, 'atlas', 40)
                    """);

            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM product
                    WHERE user_id = 7
                      AND lower(name) = 'atlas'
                    """)) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void hotLogQueriesUseTargetedIndexesAtRepresentativeScale() throws Exception {
        String schema = schemaName();
        flyway(schema, false).migrate();
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            long productId;
            try (ResultSet resultSet = statement.executeQuery("""
                    INSERT INTO product (user_id, name, price)
                    VALUES (7, 'Atlas', 10)
                    RETURNING id
                    """)) {
                resultSet.next();
                productId = resultSet.getLong(1);
            }
            statement.execute("""
                    INSERT INTO logbook (
                        user_id, action_type, timestamp, quantity, amount, product_id
                    )
                    SELECT 7,
                           CASE WHEN value %% 4 = 0 THEN 'SELL' ELSE 'ADD' END,
                           CURRENT_TIMESTAMP - (value || ' seconds')::interval,
                           1,
                           CASE WHEN value %% 4 = 0 THEN 10 ELSE NULL END,
                           %d
                    FROM generate_series(1, 100000) AS value
                    """.formatted(productId));
            statement.execute("ANALYZE logbook");

            String orderedPlan = explain(statement, """
                    SELECT id, timestamp
                    FROM logbook
                    WHERE user_id = 7
                    ORDER BY timestamp DESC, id DESC
                    LIMIT 20
                    """);
            String salesPlan = explain(statement, """
                    SELECT id, timestamp, product_id, amount, quantity
                    FROM logbook
                    WHERE user_id = 7
                      AND action_type = 'SELL'
                      AND timestamp >= CURRENT_TIMESTAMP - interval '1 day'
                    ORDER BY timestamp DESC, id DESC
                    LIMIT 20
                    """);

            assertThat(orderedPlan).contains("idx_logbook_user_timestamp_id");
            assertThat(salesPlan).contains("idx_logbook_user_action_timestamp_id");
        }
    }

    private Flyway flyway(String schema, boolean baselineOnMigrate) {
        return flyway(schema, baselineOnMigrate, null);
    }

    private Flyway flyway(
            String schema,
            boolean baselineOnMigrate,
            MigrationVersion target
    ) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                // Concurrent indexes must use session-level advisory locks, as in application.properties.
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void assertIndexDefinitions(String schema) throws Exception {
        Map<String, String> definitions = new HashMap<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT indexname, indexdef
                     FROM pg_indexes
                     WHERE schemaname = ?
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    definitions.put(
                            resultSet.getString("indexname"),
                            resultSet.getString("indexdef").toLowerCase()
                    );
                }
            }
        }

        assertThat(definitions.get("idx_logbook_user_timestamp_id"))
                .contains("(user_id, \"timestamp\" desc, id desc)");
        assertThat(definitions.get("idx_logbook_user_action_timestamp_id"))
                .contains("(user_id, action_type, \"timestamp\" desc, id desc)")
                .contains("include (product_id, amount, quantity)");
        assertThat(definitions.get("uk_product_user_active_name_ci"))
                .contains("(user_id, lower((name)::text))")
                .contains("where (deleted_at is null)");
        assertThat(definitions.get("idx_product_location_user_product_name_ci"))
                .contains("(user_id, product_id, lower((location_name)::text))")
                .contains("include (quantity)");
        assertThat(definitions).doesNotContainKeys(
                "idx_product_user_name",
                "idx_product_user_name_ci",
                "uk_product_user_lower_name",
                "uk_product_user_name"
        );
    }

    private void assertLegacyProductUniquenessRemoved(String schema) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM pg_constraint
                     WHERE connamespace = ?::regnamespace
                       AND contype = 'u'
                       AND EXISTS (
                           SELECT 1
                           FROM unnest(conkey) AS constraint_key(attnum)
                           JOIN pg_attribute attribute_record
                             ON attribute_record.attrelid = conrelid
                            AND attribute_record.attnum = constraint_key.attnum
                           WHERE attribute_record.attname = 'name'
                       )
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isZero();
            }
        }
    }

    private void assertCustomerPaymentSchema(String schema) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT column_name, data_type, numeric_precision, numeric_scale
                     FROM information_schema.columns
                     WHERE table_schema = ?
                       AND table_name = 'customer_payment_entry'
                     """)) {
            statement.setString(1, schema);
            Map<String, String> columns = new HashMap<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.put(
                            resultSet.getString("column_name"),
                            resultSet.getString("data_type") + ":"
                                    + resultSet.getString("numeric_precision") + ":"
                                    + resultSet.getString("numeric_scale"));
                }
            }
            assertThat(columns).containsKeys(
                    "user_id", "customer_id", "entry_type", "amount", "currency",
                    "method", "occurred_at", "created_at", "reversal_of_payment_id",
                    "idempotency_key", "request_fingerprint");
            assertThat(columns.get("amount")).isEqualTo("numeric:19:2");
        }

        Map<String, String> definitions = new HashMap<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT indexname, indexdef
                     FROM pg_indexes
                     WHERE schemaname = ?
                       AND tablename = 'customer_payment_entry'
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    definitions.put(
                            resultSet.getString("indexname"),
                            resultSet.getString("indexdef").toLowerCase());
                }
            }
        }
        assertThat(definitions.get("idx_customer_payment_history"))
                .contains("(user_id, customer_id, occurred_at desc, id desc)");
        assertThat(definitions.get("idx_customer_payment_balance"))
                .contains("(user_id, customer_id, entry_type)")
                .contains("include (amount)");
        assertThat(definitions.get("uk_customer_payment_user_idempotency"))
                .contains("(user_id, idempotency_key)");
        assertThat(definitions.get("uk_customer_payment_reversal"))
                .contains("(reversal_of_payment_id)");

        Map<String, String> constraints = new HashMap<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT constraint_record.conname,
                            constraint_record.contype::text
                     FROM pg_constraint constraint_record
                     JOIN pg_class table_record
                       ON table_record.oid = constraint_record.conrelid
                     JOIN pg_namespace schema_record
                       ON schema_record.oid = table_record.relnamespace
                     WHERE schema_record.nspname = ?
                       AND table_record.relname = 'customer_payment_entry'
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    constraints.put(resultSet.getString(1), resultSet.getString(2));
                }
            }
        }
        assertThat(constraints)
                .containsEntry("ck_customer_payment_entry_type", "c")
                .containsEntry("ck_customer_payment_amount", "c")
                .containsEntry("ck_customer_payment_currency", "c")
                .containsEntry("ck_customer_payment_shape", "c")
                .containsEntry("fk_customer_payment_customer", "f")
                .containsEntry("fk_customer_payment_reversal", "f")
                .containsEntry("uk_customer_payment_reversal", "u")
                .containsEntry("uk_customer_payment_user_idempotency", "u");

        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.triggers
                     WHERE trigger_schema = ?
                       AND event_object_table = 'customer_payment_entry'
                       AND trigger_name = 'trg_customer_payment_entry_immutable'
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }
    }

    private void assertRegistrationSchema(String schema) throws Exception {
        Map<String, String> columns = new HashMap<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT column_name, data_type, character_maximum_length, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = ?
                       AND table_name = 'users'
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.put(
                            resultSet.getString("column_name"),
                            resultSet.getString("data_type") + ":"
                                    + resultSet.getString("character_maximum_length") + ":"
                                    + resultSet.getString("is_nullable")
                    );
                }
            }
        }
        assertThat(columns.get("username")).endsWith(":YES");
        assertThat(columns.get("password")).endsWith(":NO");
        assertThat(columns.get("email")).isEqualTo("character varying:254:YES");
        assertThat(columns.get("phone")).isEqualTo("character varying:16:YES");

        Map<String, String> definitions = new HashMap<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT indexname, indexdef
                     FROM pg_indexes
                     WHERE schemaname = ?
                       AND tablename = 'users'
                     """)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    definitions.put(
                            resultSet.getString("indexname"),
                            resultSet.getString("indexdef").toLowerCase()
                    );
                }
            }
        }
        assertThat(definitions.get("uk_users_email_ci"))
                .contains("(lower((email)::text))")
                .contains("where (email is not null)");
        assertThat(definitions.get("uk_users_phone"))
                .contains("(phone)")
                .contains("where (phone is not null)");
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private String explain(Statement statement, String query) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (ResultSet resultSet = statement.executeQuery(
                "EXPLAIN (ANALYZE, BUFFERS) " + query
        )) {
            while (resultSet.next()) {
                plan.append(resultSet.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private String schemaName() {
        return "test_" + UUID.randomUUID().toString().replace("-", "");
    }
}
