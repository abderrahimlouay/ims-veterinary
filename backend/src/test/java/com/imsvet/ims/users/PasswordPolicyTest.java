package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.users.domain.PasswordPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit test (U) of ADR-039 / BR-SEC-003 password rules.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(12, Set.of("q1w2e3r4t5y6"));

    @Test
    void acceptsALongUncommonPasswordWithoutCompositionRules() {
        assertThat(policy.violations("correct horse battery")).isEmpty();
    }

    @Test
    void rejectsShortPasswordsCountingCharactersNotBytes() {
        assertThat(policy.violations("Short-pw-1")).containsExactly(PasswordPolicy.TOO_SHORT);
        assertThat(policy.violations("كلمةسرطويلة1")).isEmpty();
        assertThat(policy.violations(null)).containsExactly(PasswordPolicy.TOO_SHORT);
    }

    @Test
    void rejectsCommonPasswordsCaseInsensitively() {
        assertThat(policy.violations("Q1W2E3R4T5Y6")).containsExactly(PasswordPolicy.TOO_COMMON);
    }
}
