package db.migration;

import com.is.aigul.config.PasswordConfiguration;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class V5__hash_legacy_user_passwords extends BaseJavaMigration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(V5__hash_legacy_user_passwords.class);

    @Override
    public void migrate(Context context) throws Exception {
        PasswordEncoder passwordEncoder = PasswordConfiguration.createPasswordEncoder();
        int convertedCount = 0;
        int skippedCount = 0;

        try (PreparedStatement selectUsers = context.getConnection().prepareStatement("""
                SELECT id, password
                FROM users
                ORDER BY id
                FOR UPDATE
                """);
             PreparedStatement updatePassword = context.getConnection().prepareStatement("""
                     UPDATE users
                     SET password = ?
                     WHERE id = ?
                     """);
             ResultSet users = selectUsers.executeQuery()) {

            while (users.next()) {
                long userId = users.getLong("id");
                String storedPassword = users.getString("password");

                if (storedPassword == null || storedPassword.isBlank()) {
                    throw new FlywayException(
                            "Cannot migrate blank password for user id " + userId
                    );
                }

                if (storedPassword.startsWith(PasswordConfiguration.ENCODED_PASSWORD_PREFIX)) {
                    if (!PasswordConfiguration.isCurrentPasswordHash(storedPassword)) {
                        throw new FlywayException(
                                "Cannot migrate malformed Argon2 password hash for user id " + userId
                        );
                    }
                    skippedCount++;
                    continue;
                }

                updatePassword.setString(1, passwordEncoder.encode(storedPassword));
                updatePassword.setLong(2, userId);
                updatePassword.addBatch();
                convertedCount++;
            }

            if (convertedCount > 0) {
                updatePassword.executeBatch();
            }
        }

        LOGGER.info(
                "Legacy user password migration completed: converted={}, alreadyHashed={}",
                convertedCount,
                skippedCount
        );
    }
}
