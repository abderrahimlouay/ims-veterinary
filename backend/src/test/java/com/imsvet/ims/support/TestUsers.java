package com.imsvet.ims.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates users directly in the database for tests (inside the test transaction, so they roll back).
 */
public final class TestUsers {

    public static final String PASSWORD = "Tall-cedar-river-2026";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public TestUsers(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public record TestUser(long id, String username, String password) {
    }

    public TestUser create(String role) {
        return create(role, true, false);
    }

    public TestUser create(String role, boolean active, boolean mustChangePassword) {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 13);
        jdbc.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password,
                                   created_at)
                SELECT ?, ?, 'Test User', id, ?, ?, UTC_TIMESTAMP(6) FROM roles WHERE name = ?""",
                username, encoder.encode(PASSWORD), active, mustChangePassword, role);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return new TestUser(id, username, PASSWORD);
    }
}
