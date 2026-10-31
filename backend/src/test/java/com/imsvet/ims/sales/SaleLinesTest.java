package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lines of a draft: server-resolved price and amounts (BR-SALE-006/008, ADR-115, ADR-123), FEFO proposal and split
 * (BR-LOT-005/006), non-sellable lots never selectable and the override with permission and reason (BR-LOT-003/004,
 * ADR-120), discount bounds (BR-SALE-007), archived products and non-sellable units (BR-SALE-011), quotes without lot
 * or stock check (BR-SALE-003), warnings for any customer (ADR-121), drafts only (BR-SALE-004).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SaleLinesTest {

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
    private BusinessCalendar calendar;

    @PersistenceContext
    private EntityManager entities;

    private TestLots fixtures;
    private ApiClient seller;
    private ApiClient pharmacist;
    private long product;
    private long receipt;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        fixtures = new TestLots(jdbc);
        seller = client("Seller", "198.51.100.221");
        pharmacist = client("Pharmacist", "198.51.100.222");
        product = fixtures.product("SALE-1", "Amoxivet");
        jdbc.update("UPDATE products SET sale_price = 12.5 WHERE id = ?", product);
        receipt = draft("{\"docType\":\"receipt\"}");
    }

    @Test
    void theServerResolvesPriceVatAndTotalsAndIgnoresClientAmounts() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "10");
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"3\",\"unitPrice\":\"0.01\","
                + "\"lineHt\":\"1\",\"totalTtc\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value("12.5000"))
                .andExpect(jsonPath("$.lines[0].vatRate").value("9.00"))
                .andExpect(jsonPath("$.lines[0].lineHt").value("37.50"))
                .andExpect(jsonPath("$.lines[0].lineVat").value("3.38"))
                .andExpect(jsonPath("$.lines[0].lineTtc").value("40.88"))
                .andExpect(jsonPath("$.totals.ht").value("37.50"))
                .andExpect(jsonPath("$.totals.vat").value("3.38"))
                .andExpect(jsonPath("$.totals.ttc").value("40.88"))
                .andExpect(jsonPath("$.number").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'sale'", Long.class))
                .isZero();
    }

    @Test
    void generatedColumnsEqualTheServiceFormulaWithFourDecimalPrices() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "100");
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"7\",\"manualUnitPrice\":\"0.1234\","
                + "\"manualDiscountPct\":\"12.50\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lineHt").value("0.76"))
                .andExpect(jsonPath("$.lines[0].lineVat").value("0.07"))
                .andExpect(jsonPath("$.totals.discount").value("0.10"));
        assertThat(jdbc.queryForObject("SELECT line_ht FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("0.76");
        assertThat(jdbc.queryForObject("SELECT line_vat FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("0.07");
        assertThat(jdbc.queryForObject("SELECT line_ttc FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("0.83");
    }

    @Test
    void fefoSplitsAcrossLotsAndCountsWhatTheDraftAlreadyHolds() throws Exception {
        long later = fixtures.lot(product, "LATE", today.plusYears(2), "10");
        long sooner = fixtures.lot(product, "SOON", today.plusYears(1), "4");
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"6\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lotId").value(sooner))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("SOON"))
                .andExpect(jsonPath("$.lines[0].quantity").value("4.000"))
                .andExpect(jsonPath("$.lines[1].lotId").value(later))
                .andExpect(jsonPath("$.lines[1].quantity").value("2.000"));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[2].lotId").value(later));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"8\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
    }

    @Test
    void fefoSkipsNonSellableLotsWhichCanNeverBeChosen() throws Exception {
        long expired = fixtures.lot(product, "EXP", today.minusDays(1), "5");
        long blocked = fixtures.lot(product, "BLK", today.plusMonths(13), "5", "blocked");
        long recalled = fixtures.lot(product, "RCL", today.plusMonths(14), "5", "recalled");
        long good = fixtures.lot(product, "OK", today.plusMonths(15), "5");
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lotId").value(good));
        String template = "{\"productId\":" + product + ",\"quantity\":\"1\",\"fefoOverrideReason\":\"demande\",\"lotId\":";
        add(receipt, pharmacist, template + expired + "}").andExpect(jsonPath("$.code").value("LOT_EXPIRED"));
        add(receipt, pharmacist, template + blocked + "}").andExpect(jsonPath("$.code").value("LOT_BLOCKED"));
        add(receipt, pharmacist, template + recalled + "}").andExpect(jsonPath("$.code").value("LOT_RECALLED"));
    }

    @Test
    void anotherLotNeedsThePermissionAndAReasonAndIsAudited() throws Exception {
        long sooner = fixtures.lot(product, "SOON", today.plusYears(1), "5");
        long later = fixtures.lot(product, "LATE", today.plusYears(2), "5");
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + later
                + ",\"fefoOverrideReason\":\"client\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FEFO_VIOLATION"));
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + later + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fefoOverrideReason"));
        assertThat(audits("FEFO_OVERRIDE")).isZero();
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + later
                + ",\"fefoOverrideReason\":\"lot demandé\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lotId").value(later));
        assertThat(audits("FEFO_OVERRIDE")).isEqualTo(1);
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + sooner + "}")
                .andExpect(status().isOk());
        assertThat(audits("FEFO_OVERRIDE")).isEqualTo(1);
    }

    @Test
    void aChosenLotMustBelongToTheProductAndHoldTheQuantity() throws Exception {
        long other = fixtures.product("SALE-2", "Autre");
        long foreign = fixtures.lot(other, "F1", today.plusYears(1), "5");
        long small = fixtures.lot(product, "S1", today.plusYears(1), "2");
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + foreign + "}")
                .andExpect(jsonPath("$.code").value("LOT_PRODUCT_MISMATCH"));
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"3\",\"lotId\":" + small
                + ",\"fefoOverrideReason\":\"r\"}").andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
    }

    @Test
    void customerPriceAndCategoryDiscountFollowThePrecedence() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "50");
        long plain = fixtures.product("SALE-3", "Sans prix");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", plain);
        fixtures.lot(plain, "L3", today.plusYears(1), "50");
        jdbc.update("INSERT INTO customer_categories (name, discount_pct, created_at, updated_at)"
                + " VALUES ('Grossistes', 10, NOW(6), NOW(6))");
        long category = jdbc.queryForObject("SELECT id FROM customer_categories WHERE name = 'Grossistes'", Long.class);
        long customer = JSON.readTree(seller.post("/api/customers",
                "{\"name\":\"Ferme Démo Ligne\",\"categoryId\":" + category + "}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        jdbc.update("INSERT INTO customer_product_prices (customer_id, product_id, unit_price, created_at, updated_at)"
                + " VALUES (?, ?, 8, NOW(6), NOW(6))", customer, product);
        long invoice = draft("{\"docType\":\"invoice\",\"customerId\":" + customer + "}");
        add(invoice, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value("8.0000"))
                .andExpect(jsonPath("$.lines[0].discountPct").value("0.00"));
        add(invoice, seller, "{\"productId\":" + plain + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].unitPrice").value("10.0000"))
                .andExpect(jsonPath("$.lines[1].discountPct").value("10.00"));
    }

    @Test
    void manualPriceAndExcessDiscountNeedTheirPermissionAndAreAudited() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "50");
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualDiscountPct\":\"5\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualDiscountPct\":\"5\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].discountPct").value("5.00"));
        assertThat(audits("DISCOUNT_OVERRIDE")).isEqualTo(1);
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualDiscountPct\":\"101\"}")
                .andExpect(status().isBadRequest());
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualDiscountPct\":\"1.234\"}")
                .andExpect(status().isBadRequest());
        add(receipt, pharmacist, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualUnitPrice\":\"-1\"}")
                .andExpect(status().isBadRequest());
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualUnitPrice\":\"9\"}")
                .andExpect(status().isForbidden());
        ApiClient admin = client("Administrator", "198.51.100.223");
        add(receipt, admin, "{\"productId\":" + product + ",\"quantity\":\"1\",\"manualUnitPrice\":\"9\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[1].unitPrice").value("9.0000"));
        assertThat(audits("PRICE_CHANGE")).isEqualTo(1);
    }

    @Test
    void archivedProductsNonSellableUnitsAndBadQuantitiesAreRefused() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "50");
        long unit = unit("carton", "10", false);
        add(receipt, seller, "{\"productId\":" + product + ",\"unitId\":" + unit + ",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.code").value("UNIT_NOT_SELLABLE"));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"0\"}")
                .andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1.5\"}")
                .andExpect(jsonPath("$.errors[0].code").value("WHOLE_NUMBER"));
        add(receipt, seller, "{\"productId\":" + product + "}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        add(receipt, seller, "{\"quantity\":\"1\"}").andExpect(jsonPath("$.errors[0].field").value("productId"));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"targetSpeciesId\":999999}")
                .andExpect(jsonPath("$.errors[0].field").value("targetSpeciesId"));
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", product);
        entities.clear();
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    @Test
    void aUnitQuantitySplitsInThatUnitOrAsksForTheBaseUnit() throws Exception {
        fixtures.lot(product, "SOON", today.plusYears(1), "5");
        fixtures.lot(product, "LATE", today.plusYears(2), "50");
        long box = unit("boîte", "10", true);
        add(receipt, seller, "{\"productId\":" + product + ",\"unitId\":" + box + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value("0.500"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("5.000"))
                .andExpect(jsonPath("$.lines[0].unitName").value("boîte"))
                .andExpect(jsonPath("$.lines[1].quantity").value("1.500"))
                .andExpect(jsonPath("$.lines[1].unitPrice").value("125.0000"));
        long third = unit("lot de 3", "3", true);
        long other = draft("{\"docType\":\"receipt\"}");
        add(other, seller, "{\"productId\":" + product + ",\"unitId\":" + third + ",\"quantity\":\"2\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SPLIT_INEXACT"));
    }

    @Test
    void aQuoteHasNoLotAndChecksNoStock() throws Exception {
        long customer = JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Démo Devis\"}")
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        long quote = draft("{\"docType\":\"quote\",\"customerId\":" + customer + "}");
        add(quote, seller, "{\"productId\":" + product + ",\"quantity\":\"1000\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lotId").isEmpty())
                .andExpect(jsonPath("$.lines[0].quantityBase").value("1000.000"));
        long lot = fixtures.lot(product, "L1", today.plusYears(1), "5");
        add(quote, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"lotId\":" + lot + "}")
                .andExpect(jsonPath("$.errors[0].field").value("lotId"));
    }

    @Test
    void withdrawalWarningsFollowTheSpeciesForAnyCustomer() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "50");
        int bovine = species("Bovine");
        int ovine = species("Ovine");
        jdbc.update("INSERT INTO product_withdrawal_periods (product_id, species_id, meat_days, milk_days)"
                + " VALUES (?, ?, 28, 7)", product, bovine);
        jdbc.update("INSERT INTO product_withdrawal_periods (product_id, species_id, meat_days) VALUES (?, ?, 14)",
                product, ovine);
        entities.clear();
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].warnings.length()").value(2))
                .andExpect(jsonPath("$.lines[0].warnings[0].code").value("WITHDRAWAL_PERIOD"));
        long other = draft("{\"docType\":\"receipt\"}");
        add(other, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"targetSpeciesId\":" + ovine + "}")
                .andExpect(jsonPath("$.lines[0].warnings.length()").value(1))
                .andExpect(jsonPath("$.lines[0].warnings[0].speciesId").value(ovine))
                .andExpect(jsonPath("$.lines[0].warnings[0].meatDays").value(14));
        seller.get("/api/sales/" + other + "/withdrawal-warnings").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        long equine = draft("{\"docType\":\"receipt\"}");
        add(equine, seller, "{\"productId\":" + product + ",\"quantity\":\"1\",\"targetSpeciesId\":"
                + species("Equine") + "}").andExpect(jsonPath("$.lines[0].warnings").isEmpty());
    }

    @Test
    void nearExpiryAndLowStockWarnWithoutBlocking() throws Exception {
        fixtures.lot(product, "L1", today.plusDays(5), "10");
        jdbc.update("UPDATE products SET min_stock = 8 WHERE id = ?", product);
        entities.clear();
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].warnings[0].code").value("LOT_NEAR_EXPIRY"))
                .andExpect(jsonPath("$.lines[0].warnings.length()").value(1));
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"2\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].warnings[1].code").value("LOW_STOCK_AFTER_SALE"));
    }

    @Test
    void aLineIsReplacedOrRemovedAndTheTotalsFollow() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "10");
        long line = JSON.readTree(add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"10\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("lines").get(0).get("id").asLong();
        long replaced = JSON.readTree(seller.perform(put("/api/sales/" + receipt + "/lines/" + line)
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":" + product + ",\"quantity\":\"2\"}"),
                true).andExpect(status().isOk()).andExpect(jsonPath("$.totals.ht").value("25.00"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andReturn().getResponse().getContentAsString()).get("lines").get(0).get("id").asLong();
        seller.perform(delete("/api/sales/" + receipt + "/lines/999999"), true).andExpect(status().isNotFound());
        seller.perform(delete("/api/sales/" + receipt + "/lines/" + replaced), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ttc").value("0.00")).andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void onlyADraftsLinesChange() throws Exception {
        fixtures.lot(product, "L1", today.plusYears(1), "10");
        long line = JSON.readTree(add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                .andReturn().getResponse().getContentAsString()).get("lines").get(0).get("id").asLong();
        jdbc.update("UPDATE sales_documents SET status = 'confirmed', number = 'RCT-2026-000009' WHERE id = ?", receipt);
        entities.clear();
        add(receipt, seller, "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        seller.perform(delete("/api/sales/" + receipt + "/lines/" + line), true)
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
    }

    private ApiClient client(String role, String ip) throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create(role);
        ApiClient client = ApiClient.create(context, ip);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        return client;
    }

    private long draft(String json) throws Exception {
        return JSON.readTree(seller.post("/api/sales", json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions add(long document, ApiClient client, String json) throws Exception {
        return client.post("/api/sales/" + document + "/lines", json);
    }

    private long unit(String name, String factor, boolean sellable) {
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base, is_sellable) VALUES (?, ?, ?, ?)",
                product, name, new BigDecimal(factor), sellable);
        return jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ? AND name = ?", Long.class,
                product, name);
    }

    private int species(String name) {
        return jdbc.queryForObject("SELECT id FROM animal_species WHERE name = ?", Integer.class, name);
    }

    private long audits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity = 'sales_documents'"
                + " AND entity_id = ?", Long.class, action, receipt);
    }
}
