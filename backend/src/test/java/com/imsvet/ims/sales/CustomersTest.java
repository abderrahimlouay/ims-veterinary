package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Customers (FR-SALE-004, BR-CUST-001/003/005, ADR-109, ADR-110, ADR-114): defaults (breeder, credit limit 0,
 * active), search by name, phone in any format or tax id, filters; the follow-up veterinarian rules; duplicates
 * refused with ids and the field only, similar customers warned; deactivate, reactivate, never delete; personal data
 * never in the logs. All data fictional (fake-range phone numbers).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class CustomersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient seller;

    @BeforeEach
    void login() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.251");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aNewCustomerIsABreederPayingCashAndIsFoundByNamePhoneOrTaxId() throws Exception {
        int category = category("Éleveurs test");
        long id = id(create("{\"name\":\"Ferme Démo Alpha\",\"categoryId\":" + category + ",\"wilaya\":\"Wilaya Démo\","
                + "\"commune\":\"Commune Démo\",\"phone\":\"0555 00 00 01\",\"taxId\":\"nif 0001 demo\","
                + "\"paymentTermsDays\":30,\"farmingType\":\"Bovins laitiers\"}")
                .andExpect(jsonPath("$.customerType").value("breeder"))
                .andExpect(jsonPath("$.creditLimit").value("0.00"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.taxId").value("NIF0001DEMO"))
                .andExpect(jsonPath("$.category.name").value("Éleveurs test"))
                .andExpect(jsonPath("$.paymentTermsDays").value(30)));

        for (String q : List.of("alpha", "FERME DEMO", "+213 555 000 001", "00213-555-00-00-01", "NIF 0001 DEMO")) {
            seller.get("/api/customers?q=" + q)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
        }
        seller.get("/api/customers?type=veterinarian&q=alpha").andExpect(jsonPath("$.content.length()").value(0));
        seller.get("/api/customers?categoryId=" + category).andExpect(jsonPath("$.content[0].id").value(id));
        seller.get("/api/customers?wilaya=Wilaya Démo&active=true").andExpect(jsonPath("$.content[0].id").value(id));
        seller.get("/api/customers/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.farmingType").value("Bovins laitiers"));
    }

    @Test
    void fieldsAreValidated() throws Exception {
        assertFieldError(create("{\"name\":\" \"}"), "name", "REQUIRED");
        assertFieldError(create("{\"name\":\"X\",\"customerType\":\"farmer\"}"), "customerType", "UNKNOWN");
        assertFieldError(create("{\"name\":\"X\",\"categoryId\":999999}"), "categoryId", "UNKNOWN");
        assertFieldError(create("{\"name\":\"X\",\"paymentTermsDays\":-1}"), "paymentTermsDays", "RANGE");
        assertFieldError(create("{\"name\":\"X\",\"email\":\"not-an-email\"}"), "email", "EMAIL");
        assertFieldError(create("{\"name\":\"X\",\"phone\":\"0555 0000 0000 0000 01\"}"), "phone", "FORMAT");
    }

    @Test
    void theFollowUpVeterinarianIsAnActiveVeterinarianAndKeepsItsType() throws Exception {
        long vet = id(create("{\"name\":\"Cabinet Démo Vét\",\"customerType\":\"veterinarian\"}"));
        long breeder = id(create("{\"name\":\"Ferme Démo Beta\"}"));
        long followed = id(create("{\"name\":\"Ferme Démo Gamma\",\"veterinarianId\":" + vet + "}")
                .andExpect(jsonPath("$.veterinarian.id").value(vet)));

        assertFieldError(create("{\"name\":\"Ferme Démo Delta\",\"veterinarianId\":" + breeder + "}"),
                "veterinarianId", "NOT_VETERINARIAN");
        assertFieldError(update(breeder, "{\"name\":\"Ferme Démo Beta\",\"veterinarianId\":" + breeder
                + ",\"version\":0}"), "veterinarianId", "SELF");
        update(vet, "{\"name\":\"Cabinet Démo Vét\",\"customerType\":\"other\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        seller.post("/api/customers/" + vet + "/deactivate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        seller.get("/api/customers/" + followed).andExpect(jsonPath("$.veterinarian.id").value(vet));
        assertFieldError(create("{\"name\":\"Ferme Démo Epsilon\",\"veterinarianId\":" + vet + "}"),
                "veterinarianId", "NOT_VETERINARIAN");
        seller.post("/api/customers/" + vet + "/activate", "").andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void theSameNameAndPhoneOrTheSameTaxIdIsRefusedWithIdsAndTheFieldOnly() throws Exception {
        long first = id(create("{\"name\":\"Ferme Démo Zeta\",\"phone\":\"0555 00 00 02\",\"taxId\":\"NIF-DEMO-2\"}"));

        String body = create("{\"name\":\"ferme demo zeta\",\"phone\":\"+213 555.00.00.02\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.context.field").value("phone"))
                .andExpect(jsonPath("$.context.customerId").value(first))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("0555").doesNotContain("Zeta").doesNotContain("NIF");
        create("{\"name\":\"Autre Démo\",\"taxId\":\" nif-demo-2 \"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.context.field").value("taxId"))
                .andExpect(jsonPath("$.context.customerId").value(first));

        long sameName = id(create("{\"name\":\"Ferme Démo Zeta\",\"phone\":\"0555 00 00 03\"}"));
        long samePhone = id(create("{\"name\":\"Ferme Démo Eta\",\"phone\":\"0555000002\"}"));
        id(create("{\"name\":\"Ferme Démo Theta\"}"));
        id(create("{\"name\":\"Ferme Démo Iota\"}"));

        JsonNode similar = JSON.readTree(seller.get("/api/customers/similar?name=FERME DEMO ZETA"
                + "&phone=00213 555 00 00 02&excludeId=" + samePhone).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(similar.size()).isEqualTo(2);
        java.util.Map<Long, String> fields = new java.util.HashMap<>();
        similar.forEach(n -> {
            assertThat(n.properties().stream().map(java.util.Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrder("customerId", "field");
            fields.put(n.get("customerId").asLong(), n.get("field").asString());
        });
        assertThat(fields).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(first, "phone", sameName, "name"));
    }

    @Test
    void customersAreDeactivatedNeverDeletedAndEditedWithTheirVersion() throws Exception {
        long id = id(create("{\"name\":\"Ferme Démo Kappa\"}"));

        seller.perform(MockMvcRequestBuilders.delete("/api/customers/" + id), true)
                .andExpect(status().isMethodNotAllowed());
        update(id, "{\"name\":\"Ferme Démo Kappa bis\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        update(id, "{\"name\":\"Ferme Démo Kappa ter\",\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        seller.post("/api/customers/" + id + "/deactivate", "").andExpect(jsonPath("$.active").value(false));
        seller.get("/api/customers?active=false&q=kappa").andExpect(jsonPath("$.content[0].id").value(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE id = ?", Integer.class, id)).isOne();
    }

    @Test
    void anAccountantReadsButCannotManageCustomers() throws Exception {
        long id = id(create("{\"name\":\"Ferme Démo Lambda\"}"));
        TestUser accountant = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient client = ApiClient.create(context, "198.51.100.252");
        client.login(accountant.username(), accountant.password()).andExpect(status().isOk());

        client.get("/api/customers/" + id).andExpect(status().isOk());
        client.post("/api/customers", "{\"name\":\"Nope\"}").andExpect(status().isForbidden());
    }

    /** ADR-114: name, phone, email, address and tax id never reach the logs. */
    @Test
    void customerPersonalDataNeverReachesTheLogs(CapturedOutput output) throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String name = "Nom-" + marker;
        String phone = "0555 77 " + marker.substring(0, 2).replaceAll("\\D", "1") + " 99";
        String email = "courriel-" + marker + "@client.example";
        String address = "Adresse-" + marker;
        String taxId = "TAX" + marker.toUpperCase();
        long id = id(create("{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\",\"email\":\"" + email
                + "\",\"address\":\"" + address + "\",\"taxId\":\"" + taxId + "\"}"));
        update(id, "{\"name\":\"" + name + "-2\",\"phone\":\"" + phone + "\",\"email\":\"" + email
                + "\",\"address\":\"" + address + "-2\",\"taxId\":\"" + taxId + "\",\"version\":0}")
                .andExpect(status().isOk());
        create("{\"name\":\"" + name + "-2\",\"phone\":\"" + phone + "\"}").andExpect(status().isConflict());
        seller.get("/api/customers/similar?name=" + name + "&phone=" + phone.replace(" ", "")).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(name).doesNotContain(phone).doesNotContain(phone.replace(" ", ""))
                .doesNotContain(email).doesNotContain(address).doesNotContain(taxId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity = 'customers' AND entity_id = ?",
                Integer.class, id)).isZero();
    }

    private int category(String name) throws Exception {
        TestUser pharmacist = new TestUsers(jdbc, encoder).create("Pharmacist");
        ApiClient manager = ApiClient.create(context, "198.51.100.253");
        manager.login(pharmacist.username(), pharmacist.password()).andExpect(status().isOk());
        return (int) id(manager.post("/api/customer-categories", "{\"name\":\"" + name + "\",\"discountPct\":\"5\"}"));
    }

    private ResultActions create(String body) throws Exception {
        return seller.post("/api/customers", body);
    }

    private ResultActions update(long id, String body) throws Exception {
        return seller.perform(MockMvcRequestBuilders.put("/api/customers/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private static long id(ResultActions actions) throws Exception {
        JsonNode node = JSON.readTree(actions.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        return node.get("id").asLong();
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
