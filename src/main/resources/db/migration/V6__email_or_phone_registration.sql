ALTER TABLE users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN phone VARCHAR(16);

ALTER TABLE users
    ALTER COLUMN username DROP NOT NULL;

CREATE UNIQUE INDEX uk_users_email_ci
    ON users (lower(email))
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX uk_users_phone
    ON users (phone)
    WHERE phone IS NOT NULL;
