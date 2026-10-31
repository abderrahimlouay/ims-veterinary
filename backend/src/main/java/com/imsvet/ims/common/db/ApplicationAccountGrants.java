package com.imsvet.ims.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * ADR-087, BR-SEC-006: after every migration, run with the migration account, grants the application account
 * {@code SELECT, INSERT, UPDATE, DELETE} on each application table and only {@code SELECT, INSERT} on the append-only
 * tables, revoking an older broader grant there ({@code REVOKE IF EXISTS}, MySQL 8.0.30+; production runs 8.4, ADR-032);
 * never any DDL. New tables are covered without a migration of their own.
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
public class ApplicationAccountGrants implements Callback {

    /** Ledgers whose rows are never changed or removed (ADR-017, BR-STOCK-003, BR-AUDIT-002). */
    static final Set<String> APPEND_ONLY = Set.of("audit_log", "cash_movements", "stock_movements");

    private static final Pattern ACCOUNT_PART = Pattern.compile("[A-Za-z0-9_.%-]{1,64}");

    private final String applicationUser;
    private final String applicationHost;
    private final String historyTable;

    public ApplicationAccountGrants(@Value("${spring.datasource.username}") String applicationUser,
            @Value("${ims.db.application-host}") String applicationHost,
            @Value("${spring.flyway.table:flyway_schema_history}") String historyTable) {
        this.applicationUser = checked(applicationUser);
        this.applicationHost = checked(applicationHost);
        this.historyTable = historyTable;
    }

    /**
     * Spring's binder leaves an unset {@code ${IMS_DB_MIGRATION_…}} as text, which would only fail later as a login
     * error. Reading the Flyway account strictly here (this callback is created before Flyway runs) stops the start
     * at once with {@link MissingDatabaseAccountAnalyzer} or {@link SharedDatabaseAccountAnalyzer}.
     */
    @Autowired
    ApplicationAccountGrants(@Value("${spring.datasource.username}") String applicationUser,
            @Value("${ims.db.application-host}") String applicationHost,
            @Value("${spring.flyway.table:flyway_schema_history}") String historyTable,
            @Value("${spring.flyway.user}") String migrationUser,
            @Value("${spring.flyway.password}") String migrationPassword) {
        this(applicationUser, applicationHost, historyTable);
        if (migrationPassword.isBlank()) {
            throw new IllegalStateException("The migration account password is empty. " + DatabaseAccountsGuidance.ACTION);
        }
        if (migrationUser.equalsIgnoreCase(applicationUser)) {
            throw new SharedDatabaseAccountException(applicationUser);
        }
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            MySqlVersion.requireSupported(version(connection));
            String migrator = currentUser(connection);
            if (migrator.equalsIgnoreCase(applicationUser)) {
                throw new SharedDatabaseAccountException(applicationUser);
            }
            String grantee = "'" + applicationUser + "'@'" + applicationHost + "'";
            try (Statement sql = connection.createStatement()) {
                for (String table : tables(connection)) {
                    String target = "`" + table + "`";
                    if (APPEND_ONLY.contains(table)) {
                        sql.execute("GRANT SELECT, INSERT ON " + target + " TO " + grantee);
                        sql.execute("REVOKE IF EXISTS UPDATE, DELETE ON " + target + " FROM " + grantee);
                    } else {
                        sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + target + " TO " + grantee);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Granting the application account failed (ADR-087)", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "application-account-grants";
    }

    private List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> ?""")) {
            query.setString(1, historyTable);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
        }
        return tables;
    }

    private static String version(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery("SELECT VERSION()")) {
            row.next();
            return row.getString(1);
        }
    }

    private static String currentUser(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery("SELECT CURRENT_USER()")) {
            row.next();
            String user = row.getString(1);
            return user.substring(0, user.indexOf('@'));
        }
    }

    /** Account names are interpolated into GRANT statements: only plain identifiers are accepted. */
    private static String checked(String part) {
        if (part == null || !ACCOUNT_PART.matcher(part).matches()) {
            throw new IllegalStateException("Invalid database account part for the grants (ADR-087)");
        }
        return part;
    }
}
