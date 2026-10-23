package com.imsvet.ims.inventory;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Lot reads (api.md §4.4, FR-LOT-004/005/007): effective status (BR-LOT-009, ADR-059), expiry against the business
 * date in the business time zone (BR-LOT-003, ADR-055), alert levels from the {@code expiry_alert_days} setting
 * (BR-LOT-010, ADR-056), filters, FEFO order per product, and no route that writes a lot (BR-LOT-011, ADR-064).
 * The clock starts at 2026-10-20 08:00 UTC, i.e. business date 2026-10-20 in Africa/Algiers; moving it past the
 * session timeout needs a new login.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LotQueriesTest {

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
    private ApiClient seller;
    private TestLots lots;
    private long amoxival;
    private long expired;
    private long soon;
    private long blocked;
    private long depleted;

    @BeforeEach
    void lots() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.160");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        lots = new TestLots(jdbc);
        amoxival = lots.product("LOT-AMX", "Amoxival 500");
        depleted = lots.lot(amoxival, "L-LATER", LocalDate.of(2027, 6, 30), "0");
        blocked = lots.lot(amoxival, "L-BLK", TODAY.plusDays(51), "3", "blocked");
        soon = lots.lot(amoxival, "L-SOON", TODAY.plusDays(12), "10");
        expired = lots.lot(amoxival, "L-OLD", TODAY.minusDays(1), "5");
        lots.lot(lots.product("LOT-OTHER", "Other"), "B-1", TODAY.plusDays(100), "2", "recalled");
    }

    @Test
    void listsLotsByExpiryWithEffectiveStatusAndAlertLevel() throws Exception {
        seller.get("/api/lots?productId=" + amoxival).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].id").value(expired))
                .andExpect(jsonPath("$.content[0].lotNumber").value("L-OLD"))
                .andExpect(jsonPath("$.content[0].expiryDate").value("2026-10-19"))
                .andExpect(jsonPath("$.content[0].quantityOnHand").value("5.000"))
                .andExpect(jsonPath("$.content[0].status").value("expired"))
                .andExpect(jsonPath("$.content[0].alertLevel").value("expired"))
                .andExpect(jsonPath("$.content[0].daysLeft").value(-1))
                .andExpect(jsonPath("$.content[0].product.sku").value("LOT-AMX"))
                .andExpect(jsonPath("$.content[0].product.baseUnit").value("tablet"))
                .andExpect(jsonPath("$.content[1].status").value("active"))
                .andExpect(jsonPath("$.content[1].alertLevel").value("critical"))
                .andExpect(jsonPath("$.content[1].daysLeft").value(12))
                .andExpect(jsonPath("$.content[2].status").value("blocked"))
                .andExpect(jsonPath("$.content[2].alertLevel").value("notice"))
                .andExpect(jsonPath("$.content[3].status").value("depleted"))
                .andExpect(jsonPath("$.content[3].alertLevel").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(4));
    }

    @Test
    void filtersByEffectiveStatusStockExpiryWindowAndLotNumber() throws Exception {
        seller.get("/api/lots?status=expired").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(expired));
        seller.get("/api/lots?status=depleted").andExpect(jsonPath("$.content[0].id").value(depleted));
        seller.get("/api/lots?status=active").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(soon));
        seller.get("/api/lots?status=recalled").andExpect(jsonPath("$.content[0].lotNumber").value("B-1"));
        seller.get("/api/lots?productId=" + amoxival + "&hasStock=true")
                .andExpect(jsonPath("$.content.length()").value(3));
        seller.get("/api/lots?expiringWithinDays=30").andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[1].id").value(soon));
        seller.get("/api/lots?q=soon").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(soon));
        seller.get("/api/lots?status=sold").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void theDetailAddsCostBlockingAndSellability() throws Exception {
        jdbc.update("UPDATE users SET full_name = 'Karim Haddad' WHERE id = ?", user.id());
        jdbc.update("UPDATE lots SET blocked_by = ?, blocked_reason = 'Check' WHERE id = ?", user.id(), blocked);
        entityManager.clear();

        seller.get("/api/lots/" + soon).andExpect(status().isOk())
                .andExpect(jsonPath("$.unitCost").value("0.5000"))
                .andExpect(jsonPath("$.receivedAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.sellable").value(true))
                .andExpect(jsonPath("$.blockedReason").doesNotExist())
                .andExpect(jsonPath("$.blockedByName").doesNotExist());
        seller.get("/api/lots/" + blocked).andExpect(jsonPath("$.sellable").value(false))
                .andExpect(jsonPath("$.blockedBy").value(user.id()))
                .andExpect(jsonPath("$.blockedByName").value("Karim Haddad"));
        seller.get("/api/lots/" + expired).andExpect(jsonPath("$.sellable").value(false));
        seller.get("/api/lots/999999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theLotsOfAProductComeInFefoOrder() throws Exception {
        seller.get("/api/products/" + amoxival + "/lots").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(expired))
                .andExpect(jsonPath("$[1].id").value(soon))
                .andExpect(jsonPath("$[2].id").value(blocked))
                .andExpect(jsonPath("$[3].id").value(depleted));
        seller.get("/api/products/999999/lots").andExpect(status().isNotFound());
    }

    @Test
    void alertLevelsFollowTheConfiguredThresholds() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '90,60,20' WHERE setting_key = 'expiry_alert_days'");

        seller.get("/api/lots/" + blocked).andExpect(jsonPath("$.alertLevel").value("warning"));
        seller.get("/api/lots/" + depleted).andExpect(jsonPath("$.alertLevel").doesNotExist());
    }

    @Test
    void expiryUsesTheBusinessDateNotTheUtcDate() throws Exception {
        long edge = lots.lot(amoxival, "L-EDGE", TODAY, "1");
        seller.get("/api/lots/" + edge).andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.daysLeft").value(0));

        clock.set(Instant.parse("2026-10-20T23:30:00Z"));
        seller = ApiClient.create(context, "198.51.100.161");
        seller.login(user.username(), user.password()).andExpect(status().isOk());

        seller.get("/api/lots/" + edge).andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.daysLeft").value(-1))
                .andExpect(jsonPath("$.sellable").value(false));
    }

    @Test
    void noRouteCreatesOrEditsALot() throws Exception {
        seller.post("/api/lots", "{\"productId\":" + amoxival + ",\"lotNumber\":\"X\",\"expiryDate\":\"2027-01-01\"}")
                .andExpect(status().isMethodNotAllowed());
        seller.perform(MockMvcRequestBuilders.put("/api/lots/" + soon).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotNumber\":\"Y\"}"), true).andExpect(status().isMethodNotAllowed());
        seller.perform(MockMvcRequestBuilders.patch("/api/lots/" + soon).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiryDate\":\"2030-01-01\"}"), true).andExpect(status().isMethodNotAllowed());
    }
}
