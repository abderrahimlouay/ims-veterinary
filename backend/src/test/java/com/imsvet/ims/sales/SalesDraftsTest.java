package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draft sales documents: no number and no side effect (ADR-122, BR-SALE-003), anonymous only on a counter receipt and
 * credit only with a known active customer (BR-SALE-012, BR-CUST-004, DB CHECK too), only drafts change (BR-SALE-004),
 * an abandoned draft stays cancelled without a number; read with sale.read, written with sale.create.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SalesDraftsTest {

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

    private ApiClient seller;
    private TestUser sellerUser;
    private long customer;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        sellerUser = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.231");
        seller.login(sellerUser.username(), sellerUser.password()).andExpect(status().isOk());
        customer = idOf(seller.post("/api/customers", "{\"name\":\"Ferme Démo Vente\"}")
                .andExpect(status().isCreated()));
    }

    @Test
    void aDraftHasNoNumberNoTotalsAndNoLines() throws Exception {
        long id = idOf(seller.post("/api/sales", "{\"docType\":\"invoice\",\"customerId\":" + customer
                + ",\"notes\":\"  à livrer  \"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.notes").value("à livrer"))
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.totals.ttc").value("0.00"))
                .andExpect(jsonPath("$.lines").isEmpty()));
        seller.get("/api/sales/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.docType").value("invoice"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_sequences WHERE doc_key = 'INV'", Long.class))
                .isZero();
    }

    @Test
    void onlyACounterReceiptMayBeAnonymous() throws Exception {
        seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer").isEmpty());
        for (String type : new String[] {"quote", "delivery_note", "invoice"}) {
            seller.post("/api/sales", "{\"docType\":\"" + type + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        }
    }

    @Test
    void aCreditSaleNeedsAKnownActiveCustomerAndIsNeverAReceipt() throws Exception {
        seller.post("/api/sales", "{\"docType\":\"invoice\",\"creditSale\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_REQUIRED_FOR_CREDIT"));
        seller.post("/api/sales", "{\"docType\":\"receipt\",\"creditSale\":true,\"customerId\":" + customer + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("creditSale"));
        seller.post("/api/sales", "{\"docType\":\"invoice\",\"customerId\":999999}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customer);
        entities.clear();
        seller.post("/api/sales", "{\"docType\":\"invoice\",\"creditSale\":true,\"customerId\":" + customer + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
    }

    @Test
    void onlyACreditSaleHasADueDate() throws Exception {
        seller.post("/api/sales", "{\"docType\":\"invoice\",\"customerId\":" + customer
                + ",\"dueDate\":\"2027-01-31\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dueDate"));
        seller.post("/api/sales", "{\"docType\":\"invoice\",\"creditSale\":true,\"customerId\":" + customer
                + ",\"dueDate\":\"2027-01-31\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditSale").value(true))
                .andExpect(jsonPath("$.dueDate").value("2027-01-31"));
    }

    @Test
    void creditNotesAndUnknownTypesAreRefused() throws Exception {
        seller.post("/api/sales", "{\"docType\":\"credit_note\",\"customerId\":" + customer + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_CREATABLE"));
        seller.post("/api/sales", "{\"docType\":\"bon\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        seller.post("/api/sales", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void aDraftHeaderIsEditedWithItsVersion() throws Exception {
        long id = idOf(seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated()));
        send(id, "{\"customerId\":" + customer + ",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.docType").value("receipt"))
                .andExpect(jsonPath("$.version").value(1));
        send(id, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void aConfirmedDocumentCannotChangeOrBeAbandoned() throws Exception {
        long id = idOf(seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated()));
        jdbc.update("UPDATE sales_documents SET status = 'confirmed', number = 'RCT-2026-000001' WHERE id = ?", id);
        entities.clear();
        send(id, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        abandon(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
    }

    @Test
    void anAbandonedDraftStaysCancelledWithoutANumber() throws Exception {
        long id = idOf(seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated()));
        abandon(id).andExpect(status().isNoContent());
        seller.get("/api/sales/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM sales_documents WHERE id = ?", Long.class, id))
                .isEqualTo(sellerUser.id());
        abandon(id).andExpect(status().isConflict());
    }

    @Test
    void theListFiltersAndShowsNewestFirst() throws Exception {
        long receipt = idOf(seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated()));
        long quote = idOf(seller.post("/api/sales", "{\"docType\":\"quote\",\"customerId\":" + customer + "}")
                .andExpect(status().isCreated()));
        seller.get("/api/sales").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(quote))
                .andExpect(jsonPath("$.content[0].customer.name").value("Ferme Démo Vente"))
                .andExpect(jsonPath("$.content[1].id").value(receipt))
                .andExpect(jsonPath("$.content[1].customer").isEmpty());
        seller.get("/api/sales?docType=receipt").andExpect(jsonPath("$.content.length()").value(1));
        seller.get("/api/sales?customerId=" + customer).andExpect(jsonPath("$.content[0].id").value(quote));
        seller.get("/api/sales?status=confirmed").andExpect(jsonPath("$.content").isEmpty());
        seller.get("/api/sales?status=nope").andExpect(status().isBadRequest());
    }

    @Test
    void theListFiltersByBusinessDateInclusively() throws Exception {
        seller.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated());
        String today = calendar.today().toString();
        seller.get("/api/sales?from=" + today + "&to=" + today).andExpect(jsonPath("$.content.length()").value(1));
        seller.get("/api/sales?to=2000-01-01").andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void permissionsAreEnforced() throws Exception {
        TestUser accountantUser = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient accountant = ApiClient.create(context, "198.51.100.232");
        accountant.login(accountantUser.username(), accountantUser.password()).andExpect(status().isOk());
        accountant.get("/api/sales").andExpect(status().isOk());
        accountant.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void theDatabaseEnforcesTheRulesToo() throws Exception {
        long user = sellerUser.id();
        assertRefused("ck_sales_documents_credit_customer",
                "INSERT INTO sales_documents (doc_type, is_credit_sale, created_by, created_at, updated_at)"
                + " VALUES ('invoice', TRUE, " + user + ", NOW(6), NOW(6))");
        assertRefused("ck_sales_documents_number",
                "INSERT INTO sales_documents (doc_type, status, created_by, created_at, updated_at)"
                + " VALUES ('invoice', 'confirmed', " + user + ", NOW(6), NOW(6))");
        assertRefused("ck_sales_documents_draft_number",
                "INSERT INTO sales_documents (doc_type, number, created_by, created_at, updated_at)"
                + " VALUES ('invoice', 'INV-2026-000001', " + user + ", NOW(6), NOW(6))");
        assertRefused("ck_sales_documents_credit_type",
                "INSERT INTO sales_documents (doc_type, is_credit_sale, customer_id, created_by, created_at,"
                + " updated_at) VALUES ('receipt', TRUE, " + customer + ", " + user + ", NOW(6), NOW(6))");
        assertRefused("ck_sales_documents_type",
                "INSERT INTO sales_documents (doc_type, created_by, created_at, updated_at)"
                + " VALUES ('bon', " + user + ", NOW(6), NOW(6))");
    }

    private void assertRefused(String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    private ResultActions send(long id, String json) throws Exception {
        return seller.perform(put("/api/sales/" + id).contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    private ResultActions abandon(long id) throws Exception {
        return seller.perform(delete("/api/sales/" + id), true);
    }

    private static long idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
