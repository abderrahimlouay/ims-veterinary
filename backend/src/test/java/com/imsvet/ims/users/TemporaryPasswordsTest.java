package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.users.domain.TemporaryPasswords;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit test (U) of ADR-045 temporary passwords.
 */
class TemporaryPasswordsTest {

    @Test
    void sixteenUnambiguousCharactersInFourGroups() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String password = TemporaryPasswords.generate();
            assertThat(password).matches("[a-hjkmnp-z2-9]{4}(-[a-hjkmnp-z2-9]{4}){3}");
            seen.add(password);
        }
        assertThat(seen).hasSize(500);
    }
}
