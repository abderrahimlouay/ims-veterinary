package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ledger integrity check on demand (BR-STOCK-007, ADR-086, api.md §4.5): every lot balance against the sum of
 * its movements; each run stored with its mismatches and who asked; a mismatch logs a CRITICAL LEDGER_MISMATCH
 * security event; the latest run drives the banner; nothing is ever corrected.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class IntegrityCheckTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestUser admin;
    private ApiClient auditor;
    private long lot;

    @BeforeEach
    void lots() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        lot = lots.lot(lots.product("INTEG-1", "Integrity"), "L-INT", LocalDate.of(2027, 5, 31), "10");
        admin = new TestUsers(jdbc, encoder).create("Administrator");
        auditor = ApiClient.create(context, "198.51.100.230");
        auditor.login(admin.username(), admin.password()).andExpect(status().isOk());
    }

    @Test
    void aCleanLedgerGivesACleanStoredRun() throws Exception {
        auditor.post("/api/inventory/integrity-check", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.mismatches.length()").value(0))
                .andExpect(jsonPath("$.lotsChecked").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.startedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.triggeredBy.id").value(admin.id()));

        auditor.get("/api/inventory/integrity-check").andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(0));
    }

    @Test
    void aDriftingBalanceIsReportedLoggedAndNeverCorrected(CapturedOutput output) throws Exception {
        jdbc.update("UPDATE lots SET quantity_on_hand = 12.5 WHERE id = ?", lot);

        auditor.post("/api/inventory/integrity-check", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.mismatches[0].lotId").value(lot))
                .andExpect(jsonPath("$.mismatches[0].lotNumber").value("L-INT"))
                .andExpect(jsonPath("$.mismatches[0].balance").value("12.500"))
                .andExpect(jsonPath("$.mismatches[0].ledgerSum").value("10.000"));

        assertThat(output.getAll()).contains("LEDGER_MISMATCH").contains("CRITICAL");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("12.500");
        auditor.get("/api/inventory/integrity-check").andExpect(jsonPath("$.mismatchCount").value(1));
    }

    @Test
    void aCleanRunClearsTheBanner() throws Exception {
        jdbc.update("UPDATE lots SET quantity_on_hand = 3 WHERE id = ?", lot);
        auditor.post("/api/inventory/integrity-check", "").andExpect(jsonPath("$.mismatchCount").value(1));
        jdbc.update("UPDATE lots SET quantity_on_hand = 10 WHERE id = ?", lot);
        clock.advance(Duration.ofMinutes(1));

        auditor.post("/api/inventory/integrity-check", "").andExpect(jsonPath("$.mismatchCount").value(0));
        auditor.get("/api/inventory/integrity-check").andExpect(jsonPath("$.mismatchCount").value(0));
    }
}
