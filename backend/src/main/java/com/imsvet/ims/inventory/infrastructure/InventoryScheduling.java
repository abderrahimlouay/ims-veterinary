package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.application.IntegrityCheckJob;
import com.imsvet.ims.inventory.application.ReorderJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ticks the inventory jobs every 30 s (backend.md §12); each job decides from its setting and the clock whether to
 * run. Off with {@code ims.jobs.enabled=false} (tests call the ticks directly). Single instance assumed.
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ims.jobs.enabled", matchIfMissing = true)
class InventoryScheduling {

    private final IntegrityCheckJob integrityCheck;
    private final ReorderJob reorder;

    InventoryScheduling(IntegrityCheckJob integrityCheck, ReorderJob reorder) {
        this.integrityCheck = integrityCheck;
        this.reorder = reorder;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void tick() {
        integrityCheck.tick();
    }

    /** Separate from the integrity check, so a failure of one never skips the other. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void reorderTick() {
        reorder.tick();
    }
}
