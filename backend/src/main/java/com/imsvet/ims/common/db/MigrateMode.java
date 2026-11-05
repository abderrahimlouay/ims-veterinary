package com.imsvet.ims.common.db;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The migrate mode of a production release (ADR-151): Flyway has already applied the pending migrations and granted
 * the application account (ADR-087) while the context started; once the start is complete this reports the schema
 * version and closes the application. A failed migration never gets here: the start fails and the operator restores
 * the backup taken before (ADR-018).
 */
@Component
@Profile("migrate")
class MigrateMode {

    private static final Logger LOG = LoggerFactory.getLogger(MigrateMode.class);

    private final Flyway flyway;
    private final ConfigurableApplicationContext context;

    MigrateMode(Flyway flyway, ConfigurableApplicationContext context) {
        this.flyway = flyway;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        MigrationInfo current = flyway.info().current();
        long pending = Arrays.stream(flyway.info().pending()).count();
        LOG.info("Migrate mode: schema at version {}, {} pending; closing (ADR-151)",
                current == null ? "none" : current.getVersion(), pending);
        SpringApplication.exit(context);
    }
}
