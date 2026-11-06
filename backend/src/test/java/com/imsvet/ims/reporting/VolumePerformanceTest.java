package com.imsvet.ims.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-158: at the target volume — 300 sales documents a day for five years (450,000 documents of every type, 2.25
 * million lines and as many stock movements), 50,000 products and lots, 1,500 customers, account payments, 1,500
 * closed cash sessions and 5,000 supplier invoices, all fictional and generated from row numbers — every report over
 * a year answers in under 5 s and over a month in under 2 s, through the API. The data is committed in a database of
 * its own (this context's container) and the statistics refreshed, as after a load (ADR-091).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, VolumePerformanceTest.VolumeDatabase.class })
@Tag("volume")
class VolumePerformanceTest {

    /** A context, hence a MySQL container, of its own: the volume never reaches the other tests' database. */
    @TestConfiguration(proxyBeanMethods = false)
    static class VolumeDatabase {
    }

    private static final int DOCUMENTS = 450_000;
    private static final int PRODUCTS = 50_000;
    private static final int CUSTOMERS = 1_500;
    private static final long YEAR_MILLIS = 5_000;
    private static final long MONTH_MILLIS = 2_000;
    /** 0..999,999 from six cross-joined digits; numbers only, no input. */
    private static final String NUMBERS = """
            WITH RECURSIVE d (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM d WHERE n < 9),
                 numbers (n) AS (SELECT a.n + 10 * b.n + 100 * c.n + 1000 * e.n + 10000 * f.n + 100000 * g.n
                                 FROM d a, d b, d c, d e, d f, d g)
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private BusinessCalendar calendar;

    @Test
    void reportsAnswerWithinTheirBoundsAtTheTargetVolume() throws Exception {
        clock.reset();
        LocalDate today = calendar.today();
        long started = System.nanoTime();
        generate(today);
        System.out.printf("ADR-158 volume generated in %d s%n", (System.nanoTime() - started) / 1_000_000_000);

        TestUser admin = new TestUsers(jdbc, encoder).create("Administrator");
        ApiClient client = ApiClient.create(context, "198.51.100.180");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());
        String year = "from=" + today.minusYears(1).plusDays(1) + "&to=" + today;
        String month = "from=" + today.withDayOfMonth(1) + "&to=" + today;
        long lot = jdbc.queryForObject("SELECT id FROM lots WHERE lot_number = 'VOL-LOT-7'", Long.class);
        long customer = jdbc.queryForObject("SELECT id FROM customers WHERE name = 'Ferme Volume 7'", Long.class);

        Map<String, String> yearly = new LinkedHashMap<>();
        yearly.put("revenue by month", "/api/reports/revenue?groupBy=month&" + year);
        yearly.put("revenue by category", "/api/reports/revenue?groupBy=category&" + year);
        yearly.put("margin by category", "/api/reports/margin?groupBy=category&" + year);
        yearly.put("margin by product", "/api/reports/margin?groupBy=product&" + year);
        yearly.put("statistics by customer", "/api/reports/sales-statistics?groupBy=customer&" + year);
        yearly.put("statistics by commune", "/api/reports/sales-statistics?groupBy=commune&" + year);
        yearly.put("cash sessions", "/api/reports/cash-sessions?" + year);
        yearly.put("stock valuation by product", "/api/reports/stock-valuation?groupBy=product");
        yearly.put("customer balances", "/api/reports/customer-balances");
        yearly.put("supplier payables", "/api/reports/supplier-payables");
        yearly.put("lot traceability", "/api/reports/lot-traceability?lotId=" + lot);
        yearly.put("revenue CSV", "/api/reports/revenue?format=csv&groupBy=day&" + year);
        yearly.put("customer statement", "/api/customers/" + customer + "/statement?" + year);
        Map<String, String> monthly = new LinkedHashMap<>();
        monthly.put("revenue by day", "/api/reports/revenue?groupBy=day&" + month);
        monthly.put("margin by product", "/api/reports/margin?groupBy=product&" + month);
        monthly.put("sales list", "/api/sales?size=20");
        monthly.put("customer balance", "/api/customers/" + customer + "/balance");

        get(client, "/api/reports/revenue?" + month);
        Map<String, Long> yearMillis = measure(client, yearly);
        Map<String, Long> monthMillis = measure(client, monthly);
        System.out.printf("ADR-158 report times (ms), year: %s; month: %s%n", yearMillis, monthMillis);

        assertThat(yearMillis).allSatisfy((name, ms) -> assertThat(ms).as(name).isLessThan(YEAR_MILLIS));
        assertThat(monthMillis).allSatisfy((name, ms) -> assertThat(ms).as(name).isLessThan(MONTH_MILLIS));
    }

    private static Map<String, Long> measure(ApiClient client, Map<String, String> uris) throws Exception {
        Map<String, Long> millis = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : uris.entrySet()) {
            long start = System.nanoTime();
            get(client, e.getValue());
            millis.put(e.getKey(), (System.nanoTime() - start) / 1_000_000);
        }
        return millis;
    }

    private static void get(ApiClient client, String uri) throws Exception {
        client.perform(MockMvcRequestBuilders.get(uri), true).andExpect(status().isOk());
    }

    private void generate(LocalDate today) {
        JdbcTemplate db = migration.jdbc();
        long operator = new TestLots(jdbc).fixtureUser();
        String start = today.minusYears(5) + " 08:00:00";
        db.update("INSERT INTO therapeutic_categories (code, name) " + NUMBERS
                + " SELECT CONCAT('VOL-CAT-', n), CONCAT('Catégorie volume ', n) FROM numbers WHERE n < 40");
        db.update("INSERT INTO products (sku, commercial_name, category_id, base_unit, is_divisible, vat_rate,"
                + " purchase_price, sale_price, created_at, updated_at) " + NUMBERS + """
                 SELECT CONCAT('VOL-', n), CONCAT('Produit volume ', n),
                        (SELECT id FROM therapeutic_categories WHERE code = CONCAT('VOL-CAT-', n % 40)),
                        'tablet', FALSE, 9.00, 40, 100, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                 FROM numbers WHERE n <""" + " " + PRODUCTS);
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                SELECT id, CONCAT('VOL-LOT-', SUBSTRING(sku, 5)), DATE_ADD(?, INTERVAL id % 900 DAY), 40, 1000,
                       'active', ?
                FROM products WHERE sku LIKE 'VOL-%'""", today.minusDays(100), start);
        db.update("INSERT INTO customers (name, customer_type, wilaya, commune, credit_limit, created_at, updated_at) "
                + NUMBERS + """
                 SELECT CONCAT('Ferme Volume ', n), ELT(1 + n % 3, 'breeder', 'veterinarian', 'other'),
                        CONCAT('Wilaya ', n % 48), CONCAT('Commune ', n % 300), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                 FROM numbers WHERE n <""" + " " + CUSTOMERS);
        long firstCustomer = db.queryForObject("SELECT MIN(id) FROM customers WHERE name LIKE 'Ferme Volume %'",
                Long.class);
        db.update("INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, due_date,"
                + " issued_at, created_by, created_at, updated_at) " + NUMBERS + """
                 SELECT t.doc_type, CONCAT('V', t.n), 'confirmed',
                        IF(t.doc_type = 'receipt' AND t.n % 2 = 0, NULL, ? + t.n % ?),
                        t.doc_type IN ('invoice', 'delivery_note', 'credit_note'),
                        IF(t.doc_type = 'invoice', DATE(t.at) + INTERVAL 30 DAY, NULL), t.at, ?, t.at, t.at
                 FROM (SELECT n, TIMESTAMPADD(SECOND, n * 350, ?) AS at,
                              CASE WHEN n % 20 < 12 THEN 'receipt' WHEN n % 20 < 18 THEN 'invoice'
                                   WHEN n % 20 = 18 THEN 'delivery_note' ELSE 'credit_note' END AS doc_type
                       FROM numbers WHERE n <""" + " " + DOCUMENTS + ") t", firstCustomer, CUSTOMERS, operator, start);
        db.update("""
                UPDATE sales_documents c JOIN sales_documents p
                  ON p.doc_type = 'invoice' AND p.number = CONCAT('V', CAST(SUBSTRING(c.number, 2) AS UNSIGNED) - 2)
                SET c.parent_id = p.id, c.customer_id = p.customer_id
                WHERE c.doc_type = 'credit_note' AND c.number LIKE 'V%'""");
        db.update("""
                INSERT INTO sales_document_lines (document_id, product_id, lot_id, quantity, quantity_base, unit_price,
                    unit_cost, discount_pct, vat_rate)
                SELECT d.id, l.product_id, l.id, 1 + (d.id + k.k) % 3, 1 + (d.id + k.k) % 3, 100, 40, 0, 9.00
                FROM sales_documents d
                JOIN (SELECT 0 AS k UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) k
                JOIN lots l ON l.lot_number = CONCAT('VOL-LOT-', (CAST(SUBSTRING(d.number, 2) AS UNSIGNED) * 7
                    + k.k * 9973) % ?)
                WHERE d.number LIKE 'V%'""", PRODUCTS);
        db.update("""
                UPDATE sales_documents d JOIN (SELECT document_id, SUM(line_ht) AS ht, SUM(line_vat) AS vat
                                               FROM sales_document_lines GROUP BY document_id) s
                  ON s.document_id = d.id
                SET d.total_ht = s.ht, d.total_vat = s.vat, d.total_ttc = s.ht + s.vat
                WHERE d.number LIKE 'V%'""");
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reference_type, reference_id,
                    user_id, created_at)
                SELECT l.lot_id, l.product_id, IF(d.doc_type = 'credit_note', 'customer_return', 'sale'),
                       IF(d.doc_type = 'credit_note', l.quantity_base, -l.quantity_base), 'sales_document', d.id, ?,
                       d.issued_at
                FROM sales_document_lines l JOIN sales_documents d ON d.id = l.document_id
                WHERE d.number LIKE 'V%'""", operator);
        db.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, paid_at, created_by)
                SELECT customer_id, 'bank_transfer', total_ttc, CONCAT('VOL-PAY-', id), issued_at + INTERVAL 10 DAY, ?
                FROM sales_documents
                WHERE doc_type = 'invoice' AND number LIKE 'V%' AND CAST(SUBSTRING(number, 2) AS UNSIGNED) % 40 < 30""",
                operator);
        db.update("""
                INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)
                SELECT p.id, d.id, p.amount, p.paid_at, ?
                FROM customer_payments p JOIN sales_documents d ON p.reference = CONCAT('VOL-PAY-', d.id)""", operator);
        db.update("INSERT INTO cash_sessions (cashier_id, status, opened_at, closed_at, closed_by, opening_amount,"
                + " expected_amount, counted_amount) " + NUMBERS + """
                 SELECT ?, 'closed', TIMESTAMPADD(DAY, n, ?), TIMESTAMPADD(HOUR, 10, TIMESTAMPADD(DAY, n, ?)), ?,
                        5000, 5000 + n % 700, 5000 + n % 700 - (n % 7 = 0) * 50
                 FROM numbers WHERE n < 1500""", operator, start, start, operator);
        db.update("INSERT INTO suppliers (name, created_at, updated_at) " + NUMBERS
                + " SELECT CONCAT('Labo Volume ', n), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM numbers WHERE n < 50");
        db.update("INSERT INTO supplier_invoices (supplier_id, invoice_number, invoice_date, due_date, total_ttc,"
                + " status, created_by, created_at) " + NUMBERS + """
                 SELECT (SELECT id FROM suppliers WHERE name = CONCAT('Labo Volume ', n % 50)), CONCAT('VOL-FA-', n),
                        DATE(TIMESTAMPADD(HOUR, n * 8, ?)), DATE(TIMESTAMPADD(HOUR, n * 8 + 720, ?)), 1000 + n % 500,
                        'recorded', ?, UTC_TIMESTAMP(6)
                 FROM numbers WHERE n < 5000""", start, start, operator);
        for (String table : new String[] {"products", "lots", "customers", "sales_documents", "sales_document_lines",
            "stock_movements", "customer_payments", "payment_allocations", "cash_sessions", "supplier_invoices"}) {
            db.execute("ANALYZE TABLE " + table);
        }
    }
}
