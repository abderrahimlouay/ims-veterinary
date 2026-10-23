package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Lot actions (api.md §4.4/§5.7, W6): block, unblock and recall with a mandatory reason, each audited in the same
 * transaction (BR-AUDIT-003); transitions of BR-LOT-009/ADR-060 (recalled is final, a repeated recall returns the
 * same report without a second audit); recall report with empty buyer lists until Phase 7 (BR-LOT-008, ADR-068).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LotActionsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    /** Direct SQL changes are seen once the persistence context of the test transaction is cleared. */
    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private ApiClient pharmacist;
    private TestLots lots;
    private long lot;

    @BeforeEach
    void lot() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.180");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        lots = new TestLots(jdbc);
        lot = lots.lot(lots.product("ACT-1", "Amoxival"), "L-ACT", TODAY.plusDays(90), "12.5");
    }

    @Test
    void blockingStoresReasonUserAndTimeAndIsAudited() throws Exception {
        jdbc.update("UPDATE users SET full_name = 'Amina Benali' WHERE id = ?", user.id());
        entityManager.clear();

        action("block", "Damaged packaging").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.blockedReason").value("Damaged packaging"))
                .andExpect(jsonPath("$.blockedBy").value(user.id()))
                .andExpect(jsonPath("$.blockedByName").value("Amina Benali"))
                .andExpect(jsonPath("$.blockedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.sellable").value(false));

        Map<String, Object> audit = audit("LOT_BLOCK");
        assertThat(audit).containsEntry("user_id", user.id()).containsEntry("old_status", "active")
                .containsEntry("new_status", "blocked").containsEntry("reason", "Damaged packaging");
    }

    @Test
    void unblockingNeedsAReasonClearsTheBlockAndIsAudited() throws Exception {
        action("block", "Check").andExpect(status().isOk());

        action("unblock", "Checked, conforming").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.blockedReason").doesNotExist())
                .andExpect(jsonPath("$.blockedBy").doesNotExist())
                .andExpect(jsonPath("$.sellable").value(true));

        assertThat(audit("LOT_UNBLOCK")).containsEntry("old_status", "blocked").containsEntry("new_status", "active")
                .containsEntry("reason", "Checked, conforming");
    }

    @Test
    void transitionsOutsideTheTableAreRefused() throws Exception {
        action("unblock", "Not blocked").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        action("block", "First").andExpect(status().isOk());
        action("block", "Again").andExpect(status().isConflict());

        assertThat(auditCount("LOT_BLOCK")).isEqualTo(1);
        assertThat(auditCount("LOT_UNBLOCK")).isZero();
    }

    @Test
    void aRecallIsFinalAndReturnsTheReport() throws Exception {
        action("block", "Suspicion").andExpect(status().isOk());

        action("recall", "Manufacturer notice 2026-10-17").andExpect(status().isOk())
                .andExpect(jsonPath("$.lot.id").value(lot))
                .andExpect(jsonPath("$.lot.status").value("recalled"))
                .andExpect(jsonPath("$.lot.blockedBy").value(user.id()))
                .andExpect(jsonPath("$.lot.blockedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.buyers.length()").value(0))
                .andExpect(jsonPath("$.anonymousSales.length()").value(0))
                .andExpect(jsonPath("$.remainingStockBase").value("12.500"));
        assertThat(audit("LOT_RECALL")).containsEntry("old_status", "blocked").containsEntry("new_status", "recalled")
                .containsEntry("reason", "Manufacturer notice 2026-10-17");

        action("unblock", "Undo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        action("block", "Again").andExpect(status().isConflict());
    }

    @Test
    void repeatingARecallReturnsTheSameReportWithoutASecondAudit() throws Exception {
        action("recall", "Notice A").andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(5));

        action("recall", "Notice B").andExpect(status().isOk())
                .andExpect(jsonPath("$.lot.blockedAt").value("2026-10-20T08:00:00Z"));
        pharmacist.get("/api/lots/" + lot + "/recall-report").andExpect(status().isOk())
                .andExpect(jsonPath("$.lot.status").value("recalled"))
                .andExpect(jsonPath("$.remainingStockBase").value("12.500"));

        assertThat(auditCount("LOT_RECALL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT blocked_reason FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("Notice A");
    }

    @Test
    void theReportOfALotNotRecalledIsRefused() throws Exception {
        pharmacist.get("/api/lots/" + lot + "/recall-report").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void anExpiredOrEmptyLotCanStillBeBlocked() throws Exception {
        long expired = lots.lot(lots.product("ACT-2", "Old"), "L-OLD", TODAY.minusDays(3), "0");

        pharmacist.post("/api/lots/" + expired + "/block", "{\"reason\":\"Quarantine\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"));
    }

    @Test
    void theReasonIsMandatoryAndUnknownLotsAreNotFound() throws Exception {
        pharmacist.post("/api/lots/" + lot + "/block", "{\"reason\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        pharmacist.post("/api/lots/" + lot + "/recall", "{}").andExpect(status().isBadRequest());
        pharmacist.post("/api/lots/" + lot + "/block", "{\"reason\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SIZE"));
        pharmacist.post("/api/lots/999999/block", "{\"reason\":\"x\"}").andExpect(status().isNotFound());
        pharmacist.post("/api/lots/999999/recall", "{\"reason\":\"x\"}").andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, lot)).isEqualTo("active");
    }

    private ResultActions action(String name, String reason) throws Exception {
        return pharmacist.post("/api/lots/" + lot + "/" + name, "{\"reason\":\"" + reason + "\"}");
    }

    private Map<String, Object> audit(String action) {
        return jdbc.queryForMap("""
                SELECT user_id, old_values->>'$.status' AS old_status, new_values->>'$.status' AS new_status,
                       new_values->>'$.reason' AS reason
                FROM audit_log WHERE action = ? AND entity = 'lots' AND entity_id = ?""", action, lot);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class,
                action, lot);
    }
}
