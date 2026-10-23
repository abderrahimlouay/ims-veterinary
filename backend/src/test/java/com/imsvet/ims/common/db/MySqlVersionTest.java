package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-003 as amended: MySQL 8.0.30 or later (REVOKE IF EXISTS, ADR-087); 8.4 LTS is the target. */
class MySqlVersionTest {

    @ParameterizedTest
    @ValueSource(strings = { "8.0.30", "8.0.30-log", "8.4.6", "8.4.0-commercial", "9.1.0" })
    void supportedVersionsPass(String version) {
        assertThatCode(() -> MySqlVersion.requireSupported(version)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { "8.0.29", "8.0.16", "5.7.44", "10.11.6-MariaDB-x", "unknown" })
    void olderOrUnknownVersionsFailWithTheMinimumAndTheTarget(String version) {
        assertThatThrownBy(() -> MySqlVersion.requireSupported(version)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(version).hasMessageContaining("8.0.30").hasMessageContaining("8.4 LTS");
    }
}
