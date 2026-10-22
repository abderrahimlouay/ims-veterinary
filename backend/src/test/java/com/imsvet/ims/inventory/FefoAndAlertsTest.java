package com.imsvet.ims.inventory;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.time.LocalDate;
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
 * api.md §5.3 FEFO preview (BR-LOT-005, BR-UNIT-002/003, ADR-065) and the expiry alert list (BR-LOT-010, W12).
 * Business date 2026-10-20; thresholds 60/30/15 unless the setting is changed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class FefoAndAlertsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient pharmacist;
    private TestLots lots;
    private long solution;
    private long bottle;
    private long dose;
    private long l1;
    private long l2;

    @BeforeEach
    void stock() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.170");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        lots = new TestLots(jdbc);
        solution = product("FEFO-ML", "ml", true);
        bottle = unit(solution, "Bottle", "100", true);
        dose = unit(solution, "Dose", "2.5", false);
        l1 = lots.lot(solution, "L1", TODAY.plusDays(30), "150");
        l2 = lots.lot(solution, "L2", TODAY.plusDays(10), "40");
        lots.lot(solution, "L3", TODAY.minusDays(1), "500");
        lots.lot(solution, "L4", TODAY.plusDays(5), "100", "blocked");
    }

    @Test
    void previewsSellableLotsByExpiryInBaseUnits() throws Exception {
        fefo("productId=" + solution + "&quantity=1&unitId=" + bottle).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedBase").value("100.000"))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].lotId").value(l2))
                .andExpect(jsonPath("$.allocations[0].lotNumber").value("L2"))
                .andExpect(jsonPath("$.allocations[0].expiryDate").value("2026-10-30"))
                .andExpect(jsonPath("$.allocations[0].quantityBase").value("40.000"))
                .andExpect(jsonPath("$.allocations[0].available").value("40.000"))
                .andExpect(jsonPath("$.allocations[0].alertLevel").value("critical"))
                .andExpect(jsonPath("$.allocations[1].lotId").value(l1))
                .andExpect(jsonPath("$.allocations[1].quantityBase").value("60.000"))
                .andExpect(jsonPath("$.allocations[1].available").value("150.000"))
                .andExpect(jsonPath("$.allocations[1].alertLevel").value("warning"))
                .andExpect(jsonPath("$.shortfallBase").value("0.000"));
        fefo("productId=" + solution + "&quantity=3").andExpect(jsonPath("$.requestedBase").value("3.000"))
                .andExpect(jsonPath("$.allocations.length()").value(1));
    }

    @Test
    void reportsAShortfallWithoutReservingAnything() throws Exception {
        fefo("productId=" + solution + "&quantity=2&unitId=" + bottle)
                .andExpect(jsonPath("$.requestedBase").value("200.000"))
                .andExpect(jsonPath("$.shortfallBase").value("10.000"));

        fefo("productId=" + solution + "&quantity=2&unitId=" + bottle).andExpect(jsonPath("$.shortfallBase").value("10.000"));
    }

    @Test
    void refusesUnsellableForeignAndInexactUnits() throws Exception {
        long tablets = product("FEFO-TAB", "tablet", false);
        long box = unit(tablets, "Box", "10", true);

        fefo("productId=" + solution + "&quantity=1&unitId=" + dose).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNIT_NOT_SELLABLE"));
        assertFieldError(fefo("productId=" + solution + "&quantity=1&unitId=" + box), "unitId", "UNKNOWN");
        assertFieldError(fefo("productId=" + tablets + "&quantity=1.5"), "quantity", "WHOLE_NUMBER");
        assertFieldError(fefo("productId=" + solution + "&quantity=0.0001"), "quantity", "SCALE");
        assertFieldError(fefo("productId=" + solution + "&quantity=0"), "quantity", "POSITIVE");
        fefo("productId=" + solution).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesUnknownAndArchivedProducts() throws Exception {
        fefo("productId=999999&quantity=1").andExpect(status().isNotFound());
        pharmacist.post("/api/products/" + solution + "/archive", "").andExpect(status().isOk());

        fefo("productId=" + solution + "&quantity=1").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    @Test
    void groupsLotsWithStockByAlertLevel() throws Exception {
        long other = lots.product("ALERT-B", "Other");
        lots.lot(other, "A-WARN", TODAY.plusDays(20), "1");
        lots.lot(other, "A-NONE", TODAY.plusDays(61), "1");
        lots.lot(other, "A-EMPTY", TODAY.plusDays(3), "0");

        pharmacist.get("/api/lots/expiry-alerts").andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-10-20"))
                .andExpect(jsonPath("$.thresholds.notice").value(60))
                .andExpect(jsonPath("$.thresholds.warning").value(30))
                .andExpect(jsonPath("$.thresholds.critical").value(15))
                .andExpect(jsonPath("$.expired.length()").value(1))
                .andExpect(jsonPath("$.expired[0].lotNumber").value("L3"))
                .andExpect(jsonPath("$.critical.length()").value(2))
                .andExpect(jsonPath("$.critical[0].lotNumber").value("L4"))
                .andExpect(jsonPath("$.critical[0].status").value("blocked"))
                .andExpect(jsonPath("$.critical[1].lotNumber").value("L2"))
                .andExpect(jsonPath("$.warning.length()").value(2))
                .andExpect(jsonPath("$.notice.length()").value(0));
    }

    @Test
    void alertGroupsFollowTheConfiguredThresholds() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '90,60,20' WHERE setting_key = 'expiry_alert_days'");
        lots.lot(lots.product("ALERT-C", "Other"), "A-61", TODAY.plusDays(61), "1");

        pharmacist.get("/api/lots/expiry-alerts")
                .andExpect(jsonPath("$.thresholds.notice").value(90))
                .andExpect(jsonPath("$.notice.length()").value(1))
                .andExpect(jsonPath("$.notice[0].lotNumber").value("A-61"))
                .andExpect(jsonPath("$.warning[0].lotNumber").value("L1"));
    }

    /** The dashboard links each level to {@code /lots?alertLevel=<level>&hasStock=true}: same lots, same count. */
    @Test
    void eachAlertCountEqualsTheSizeOfItsLotList() throws Exception {
        long other = lots.product("ALERT-D", "Other");
        lots.lot(other, "D-WARN", TODAY.plusDays(20), "1");
        lots.lot(other, "D-NOTICE", TODAY.plusDays(45), "1", "blocked");
        lots.lot(other, "D-LIMIT", TODAY.plusDays(60), "1");
        lots.lot(other, "D-NONE", TODAY.plusDays(61), "1");
        lots.lot(other, "D-EMPTY", TODAY.plusDays(15), "0");
        String alerts = pharmacist.get("/api/lots/expiry-alerts").andReturn().getResponse().getContentAsString();

        for (String level : new String[] { "expired", "critical", "warning", "notice" }) {
            int count = JSON.readTree(alerts).get(level).size();
            pharmacist.get("/api/lots?alertLevel=" + level + "&hasStock=true&size=100")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(count))
                    .andExpect(jsonPath("$.content[*].alertLevel").value(everyItem(is(level))));
        }
        pharmacist.get("/api/lots?alertLevel=notice&hasStock=true").andExpect(jsonPath("$.page.totalElements").value(2));
        pharmacist.get("/api/lots?alertLevel=soon").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("alertLevel"));
    }

    private ResultActions fefo(String query) throws Exception {
        return pharmacist.get("/api/lots/fefo?" + query);
    }

    private long product(String sku, String baseUnit, boolean divisible) throws Exception {
        return idOf(pharmacist.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + sku
                + "\",\"baseUnit\":\"" + baseUnit + "\",\"divisible\":" + divisible + "}").andExpect(status().isCreated()));
    }

    private long unit(long productId, String name, String factor, boolean sellable) throws Exception {
        return idOf(pharmacist.post("/api/products/" + productId + "/units", "{\"name\":\"" + name
                + "\",\"factorToBase\":\"" + factor + "\",\"sellable\":" + sellable + "}").andExpect(status().isCreated()));
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
