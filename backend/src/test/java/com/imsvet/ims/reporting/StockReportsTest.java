package com.imsvet.ims.reporting;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * BR-RPT-002, BR-RPT-003: the stock on hand valued at cost (Σ lot quantity × lot cost) and at sale price (Σ quantity ×
 * sale price per base unit); blocked, recalled and expired lots counted and shown apart; the cost basis needs
 * {@code report.margin}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StockReportsTest {

    private static final String ROW = "$.rows[?(@.code == 'VAL-1')]";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private BusinessCalendar calendar;

    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.245");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
        long product = fixtures.product("VAL-1", "Produit valorisé");
        jdbc.update("UPDATE products SET sale_price = 1000 WHERE id = ?", product);
        fixtures.lot(product, "VAL-OK", calendar.today(), "100");                        // sellable on its expiry date
        fixtures.lot(product, "VAL-EXP", calendar.today().minusDays(1), "10");           // expired
        fixtures.lot(product, "VAL-BLK", calendar.today().plusYears(1), "5", "blocked");
        fixtures.lot(product, "VAL-NIL", calendar.today().plusYears(1), "0");            // nothing on hand
    }

    @Test
    void theStockIsValuedAtSalePriceByDefaultWithTheUnsellablePartApart() throws Exception {
        admin.get("/api/reports/stock-valuation").andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("sale_price"))
                .andExpect(jsonPath("$.date").value(calendar.today().toString()))
                .andExpect(jsonPath(ROW + ".quantityBase", contains("115.000")))
                .andExpect(jsonPath(ROW + ".baseUnit", contains("tablet")))
                .andExpect(jsonPath(ROW + ".value", contains("115000.00")))
                .andExpect(jsonPath(ROW + ".unsellableQuantityBase", contains("15.000")))
                .andExpect(jsonPath(ROW + ".unsellableValue", contains("15000.00")));
    }

    @Test
    void theCostBasisUsesTheLotCostAndNeedsTheMarginPermission() throws Exception {
        admin.get("/api/reports/stock-valuation?basis=cost").andExpect(status().isOk())
                .andExpect(jsonPath(ROW + ".value", contains("57.50")))
                .andExpect(jsonPath(ROW + ".unsellableValue", contains("7.50")));
        admin.get("/api/reports/stock-valuation?groupBy=category").andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("category"));
        admin.get("/api/reports/stock-valuation?basis=retail").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        jdbc.update("""
                DELETE rp FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.name = 'Pharmacist' AND p.code = 'report.margin'""");
        TestUser pharmacist = new TestUsers(jdbc, encoder).create("Pharmacist");
        ApiClient client = ApiClient.create(context, "198.51.100.246");
        client.login(pharmacist.username(), pharmacist.password()).andExpect(status().isOk());
        client.get("/api/reports/stock-valuation").andExpect(status().isOk());
        client.get("/api/reports/stock-valuation?basis=cost").andExpect(status().isForbidden());
    }
}
