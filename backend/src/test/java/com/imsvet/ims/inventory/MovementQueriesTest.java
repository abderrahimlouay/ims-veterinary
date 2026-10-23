package com.imsvet.ims.inventory;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
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
 * The ledger read (api.md §4.5 {@code GET /api/inventory/movements}, FR-STOCK-002): newest first, signed quantities,
 * lot, product, reason, operator name and time; filters by product, lot, type, operator and business dates.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class MovementQueriesTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private StockService stock;

    @Autowired
    private EntityManager entityManager;

    private ApiClient seller;
    private long product;
    private long lot;
    private long operator;

    @BeforeEach
    void ledger() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        product = lots.product("LEDGER-1", "Amoxival 500");
        lot = lots.lot(product, "L-LEDGER", LocalDate.of(2027, 5, 31), "20");
        lots.lot(lots.product("LEDGER-2", "Other"), "L-OTHER", LocalDate.of(2027, 5, 31), "5");
        operator = lots.fixtureUser();
        jdbc.update("UPDATE users SET full_name = 'Amina Benali' WHERE id = ?", operator);
        TestActor.signIn(operator);
        stock.post(List.of(new Movement(lot, product, MovementType.ADJUSTMENT_BREAKAGE, new BigDecimal("-2.5"),
                "Broken vial", "adjustment", null)));
        clock.advance(Duration.ofHours(1));
        stock.post(List.of(new Movement(lot, product, MovementType.ADJUSTMENT_INVENTORY, new BigDecimal("1"),
                "Inventory #3", "inventory", 3L)));
        TestActor.signOut();
        entityManager.flush();
        entityManager.clear();
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.190");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void listsTheLedgerNewestFirstWithItsOperator() throws Exception {
        seller.get("/api/inventory/movements?productId=" + product).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].type").value("adjustment_inventory"))
                .andExpect(jsonPath("$.content[0].quantity").value("1.000"))
                .andExpect(jsonPath("$.content[0].referenceType").value("inventory"))
                .andExpect(jsonPath("$.content[0].referenceId").value(3))
                .andExpect(jsonPath("$.content[1].type").value("adjustment_breakage"))
                .andExpect(jsonPath("$.content[1].quantity").value("-2.500"))
                .andExpect(jsonPath("$.content[1].reason").value("Broken vial"))
                .andExpect(jsonPath("$.content[1].user.id").value(operator))
                .andExpect(jsonPath("$.content[1].user.fullName").value("Amina Benali"))
                .andExpect(jsonPath("$.content[1].createdAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.content[1].lot.id").value(lot))
                .andExpect(jsonPath("$.content[1].lot.lotNumber").value("L-LEDGER"))
                .andExpect(jsonPath("$.content[1].lot.expiryDate").value("2027-05-31"))
                .andExpect(jsonPath("$.content[1].product.sku").value("LEDGER-1"))
                .andExpect(jsonPath("$.content[1].product.baseUnit").value("tablet"))
                .andExpect(jsonPath("$.content[2].type").value("opening_stock"));
    }

    @Test
    void filtersByLotTypeOperatorAndBusinessDates() throws Exception {
        seller.get("/api/inventory/movements?lotId=" + lot + "&type=adjustment_breakage")
                .andExpect(jsonPath("$.content.length()").value(1));
        seller.get("/api/inventory/movements?productId=" + product + "&userId=" + operator)
                .andExpect(jsonPath("$.content.length()").value(3));
        seller.get("/api/inventory/movements?productId=" + product + "&from=2026-10-20&to=2026-10-20")
                .andExpect(jsonPath("$.content.length()").value(2));
        seller.get("/api/inventory/movements?productId=" + product + "&to=2026-10-19")
                .andExpect(jsonPath("$.content.length()").value(1));
        seller.get("/api/inventory/movements?type=sale_cancellation").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }
}
