package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.CashLedger;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Cash sessions: one open per cashier (BR-CASH-001), closing computes expected and variance on cash only
 * (BR-CASH-002/003), a closed session never changes (BR-CASH-004), own session or cash.manage; ADR-126: the Pharmacist
 * has cash.open; a non-zero variance is audited.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CashSessionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private CashLedger ledger;

    @PersistenceContext
    private EntityManager entities;

    private TestUser sellerUser;
    private ApiClient seller;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        sellerUser = new TestUsers(jdbc, encoder).create("Seller");
        seller = login(sellerUser, "198.51.100.211");
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aSessionOpensOnceAndShowsItsRunningExpectedAmount() throws Exception {
        seller.get("/api/cash-sessions/current").andExpect(status().isNoContent());
        open(seller, "100").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.openingAmount").value("100.00"))
                .andExpect(jsonPath("$.expectedAmount").value("100.00"))
                .andExpect(jsonPath("$.countedAmount").isEmpty());
        open(seller, "5").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));
        seller.get("/api/cash-sessions/current").andExpect(status().isOk())
                .andExpect(jsonPath("$.cashierId").value(sellerUser.id()));
    }

    @Test
    void amountsAreValidated() throws Exception {
        open(seller, "-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        open(seller, "1.234").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        seller.post("/api/cash-sessions/open", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void closingComputesExpectedFromCashOnlyAndStoresTheVariance() throws Exception {
        long id = idOf(open(seller, "100").andExpect(status().isCreated()));
        TestActor.signIn(sellerUser.id());
        long session = ledger.lockCurrentSession();
        assertThat(session).isEqualTo(id);
        ledger.post(session, CashDirection.IN, PaymentMethod.CASH, new BigDecimal("45.50"), "vente", null);
        ledger.post(session, CashDirection.OUT, PaymentMethod.CASH, new BigDecimal("20.00"), "sortie", null);
        ledger.post(session, CashDirection.IN, PaymentMethod.CHEQUE, new BigDecimal("300.00"), "chèque", null);
        ledger.post(session, CashDirection.IN, PaymentMethod.BANK_TRANSFER, new BigDecimal("50.00"), "virement", null);
        TestActor.signOut();
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("125.50"));
        close(seller, id, "120").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.expectedAmount").value("125.50"))
                .andExpect(jsonPath("$.countedAmount").value("120.00"))
                .andExpect(jsonPath("$.varianceAmount").value("-5.50"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT variance_amount FROM cash_sessions WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("-5.50");
        assertThat(jdbc.queryForObject("SELECT closed_by FROM cash_sessions WHERE id = ?", Long.class, id))
                .isEqualTo(sellerUser.id());
        assertThat(audits(id)).isEqualTo(1);
    }

    @Test
    void aClosedSessionNeverChangesAndNothingPostsToIt() throws Exception {
        long id = idOf(open(seller, "10").andExpect(status().isCreated()));
        close(seller, id, "10").andExpect(status().isOk()).andExpect(jsonPath("$.varianceAmount").value("0.00"));
        assertThat(audits(id)).isZero();
        close(seller, id, "11").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_CLOSED"));
        TestActor.signIn(sellerUser.id());
        assertThatThrownBy(() -> ledger.post(id, CashDirection.IN, PaymentMethod.CASH, BigDecimal.ONE, "tard", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASH_SESSION_CLOSED));
        assertThatThrownBy(ledger::lockCurrentSession).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CASH_SESSION_REQUIRED));
        TestActor.signOut();
        open(seller, "20").andExpect(status().isCreated());
    }

    @Test
    void onlyTheCashierOrAManagerClosesASession() throws Exception {
        long id = idOf(open(seller, "10").andExpect(status().isCreated()));
        ApiClient otherSeller = login(new TestUsers(jdbc, encoder).create("Seller"), "198.51.100.212");
        close(otherSeller, id, "10").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        TestUser manager = new TestUsers(jdbc, encoder).create("Accountant");
        close(login(manager, "198.51.100.213"), id, "10").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT closed_by FROM cash_sessions WHERE id = ?", Long.class, id))
                .isEqualTo(manager.id());
        close(seller, 999999, "10").andExpect(status().isNotFound());
    }

    @Test
    void thePharmacistMayOpenASessionButNotManageThem() throws Exception {
        ApiClient pharmacist = login(new TestUsers(jdbc, encoder).create("Pharmacist"), "198.51.100.214");
        open(pharmacist, "10").andExpect(status().isCreated());
        pharmacist.get("/api/cash-sessions").andExpect(status().isForbidden());
        seller.get("/api/cash-sessions").andExpect(status().isForbidden());
    }

    @Test
    void managersListAndReadSessions() throws Exception {
        long id = idOf(open(seller, "10").andExpect(status().isCreated()));
        ApiClient manager = login(new TestUsers(jdbc, encoder).create("Accountant"), "198.51.100.215");
        manager.get("/api/cash-sessions").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id));
        manager.get("/api/cash-sessions/" + id).andExpect(status().isOk());
        manager.get("/api/cash-sessions/999999").andExpect(status().isNotFound());
    }

    @Test
    void theDatabaseAllowsOneOpenSessionPerCashierAndValidMovements() throws Exception {
        long id = idOf(open(seller, "10").andExpect(status().isCreated()));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_sessions (cashier_id, opened_at) VALUES (?, NOW(6))",
                sellerUser.id())).hasMessageContaining("uq_cash_sessions_open_cashier");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movements (cash_session_id, direction, method, amount,"
                + " created_by, created_at) VALUES (?, 'in', 'cash', 0, ?, NOW(6))", id, sellerUser.id()))
                .hasMessageContaining("ck_cash_movements_amount");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movements (cash_session_id, direction, method, amount,"
                + " created_by, created_at) VALUES (?, 'sideways', 'cash', 1, ?, NOW(6))", id, sellerUser.id()))
                .hasMessageContaining("ck_cash_movements_direction");
        entities.clear();
    }

    private ApiClient login(TestUser user, String ip) throws Exception {
        ApiClient client = ApiClient.create(context, ip);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        return client;
    }

    private static ResultActions open(ApiClient client, String amount) throws Exception {
        return client.post("/api/cash-sessions/open", "{\"openingAmount\":\"" + amount + "\"}");
    }

    private static ResultActions close(ApiClient client, long id, String amount) throws Exception {
        return client.post("/api/cash-sessions/" + id + "/close", "{\"countedAmount\":\"" + amount + "\"}");
    }

    private long audits(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CASH_VARIANCE' AND entity_id = ?",
                Long.class, id);
    }

    private static long idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
