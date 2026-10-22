package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.common.actor.Actor;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.inventory.application.ExpiryPolicies;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * BR-AUDIT-003 for lot actions: the audit entry is written before the lot changes, so a failed audit leaves the
 * lot as it was (and, with the recorder joining the caller's transaction, nothing is committed).
 */
class LotActionAuditFailureTest {

    @Test
    void aFailedAuditLeavesTheLotUnchanged() {
        LotRepository lots = mock(LotRepository.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        ActorProvider actors = mock(ActorProvider.class);
        Lot lot = BeanUtils.instantiateClass(Lot.class);
        ReflectionTestUtils.setField(lot, "id", 4L);
        ReflectionTestUtils.setField(lot, "status", LotStatus.ACTIVE);
        when(lots.findByIdForUpdate(4L)).thenReturn(Optional.of(lot));
        when(actors.current()).thenReturn(new Actor(7L, "198.51.100.1"));
        doThrow(new IllegalStateException("audit_log unavailable"))
                .when(audit).record(any(), anyString(), any(), anyMap(), anyMap());
        LotActions actions = new LotActions(lots, mock(ProductLookup.class), mock(ExpiryPolicies.class), audit, actors,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> actions.block(4, "Damaged")).hasMessage("audit_log unavailable");

        assertThat(lot.status()).isEqualTo(LotStatus.ACTIVE);
        assertThat(lot.blockedReason()).isNull();
    }
}
