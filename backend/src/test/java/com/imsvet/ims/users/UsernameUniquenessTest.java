package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of the users table: usernames are unique case- and accent-insensitively (ADR-041).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UsernameUniquenessTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void usernamesDifferingOnlyByCaseOrAccentAreDuplicates() {
        insertUser("ahmed", null);

        assertThatThrownBy(() -> insertUser("Ahmed", null)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertUser("AHMÉD", null)).isInstanceOf(DuplicateKeyException.class);
        insertUser("ahmed2", null);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'Ahméd'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void emailIsUniqueButOptional() {
        insertUser("user1", null);
        insertUser("user2", null);
        insertUser("user3", "a@example.org");

        assertThatThrownBy(() -> insertUser("user4", "A@example.org")).isInstanceOf(DuplicateKeyException.class);
    }

    /** MySQL reports a violated CHECK as error 3819, which Spring leaves uncategorised. */
    @Test
    void blankUsernameIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertUser("   ", null))
                .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(e.getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, sql -> {
                            assertThat(sql.getErrorCode()).isEqualTo(3819);
                            assertThat(sql.getMessage()).contains("ck_users_username_not_blank");
                        }));
    }

    @Test
    void newUserDefaultsToActiveWithoutForcedPasswordChangeAndVersionZero() {
        insertUser("defaults", null);

        var row = jdbc.queryForMap(
                "SELECT is_active, must_change_password, version FROM users WHERE username = 'defaults'");
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("must_change_password")).isEqualTo(false);
        assertThat(row.get("version")).isEqualTo(0L);
    }

    private void insertUser(String username, String email) {
        jdbc.update("""
                INSERT INTO users (username, password_hash, full_name, email, role_id, created_at)
                SELECT ?, '{bcrypt}not-a-real-hash', 'Test User', ?, id, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Seller'""", username, email);
    }
}
