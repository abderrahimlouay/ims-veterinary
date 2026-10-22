package com.imsvet.ims.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The migration account of ADR-087 (DDL and every right on the schema), for tests that must show what the
 * database refuses even to a privileged account. Deliberately not a {@code DataSource} bean: the application
 * keeps its single, restricted pool.
 */
public record MigrationAccount(JdbcTemplate jdbc) {
}
