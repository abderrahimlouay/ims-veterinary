package com.imsvet.ims.catalog;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * API test (A) of the catalogue reference lists (FR-PROD-008, api.md §4.3): seeded entries are protected
 * (ADR-054), names unique ignoring case and accents (ADR-051), unused entries deletable (ADR-053).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReferenceListsEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient pharmacist;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.110");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void listsAreSortedByNameAndCarryTheCodeOfSeededEntries() throws Exception {
        pharmacist.get("/api/animal-species").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].name").value("Bovine"))
                .andExpect(jsonPath("$[0].code").value("BOVINE"))
                .andExpect(jsonPath("$[6].name").value("Poultry"));
        pharmacist.get("/api/therapeutic-categories").andExpect(status().isNotFound());
        pharmacist.get("/api/categories").andExpect(jsonPath("$", hasSize(5)));
        pharmacist.get("/api/dosage-forms").andExpect(jsonPath("$", hasSize(5)));
        pharmacist.get("/api/manufacturers").andExpect(jsonPath("$", hasSize(0)));
        pharmacist.get("/api/active-ingredients").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void addedEntriesCanBeRenamedAndDeleted() throws Exception {
        String location = pharmacist.post("/api/manufacturers", "{\"name\":\"Vétoquinol\",\"country\":\"France\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Vétoquinol"))
                .andExpect(jsonPath("$.country").value("France"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn().getResponse().getHeader("Location");

        put(location, "{\"name\":\"Vetoquinol SA\",\"country\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vetoquinol SA"))
                .andExpect(jsonPath("$.country").doesNotExist());
        delete(location).andExpect(status().isNoContent());
        pharmacist.get("/api/manufacturers").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void namesAreUniqueIgnoringCaseAndAccents() throws Exception {
        pharmacist.post("/api/active-ingredients", "{\"name\":\"Amoxicilline\"}").andExpect(status().isCreated());

        pharmacist.post("/api/active-ingredients", "{\"name\":\"AMOXICILLINE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        pharmacist.post("/api/animal-species", "{\"name\":\"bovine\"}").andExpect(status().isConflict());
    }

    @Test
    void seededEntriesCanBeNeitherRenamedNorDeleted() throws Exception {
        long bovine = jdbc.queryForObject("SELECT id FROM animal_species WHERE code = 'BOVINE'", Long.class);

        put("/api/animal-species/" + bovine, "{\"name\":\"Cattle\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        delete("/api/animal-species/" + bovine).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        pharmacist.post("/api/dosage-forms", "{\"name\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        pharmacist.post("/api/dosage-forms", "{\"name\":\"" + "x".repeat(61) + "\"}")
                .andExpect(status().isBadRequest());
        put("/api/categories/999999", "{\"name\":\"X\"}").andExpect(status().isNotFound());
        delete("/api/categories/999999").andExpect(status().isNotFound());
    }

    @Test
    void readersWithoutReferenceManageCannotChangeLists() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.111");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isOk());

        sellerClient.get("/api/animal-species").andExpect(status().isOk());
        sellerClient.post("/api/manufacturers", "{\"name\":\"X\"}").andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    private ResultActions put(String uri, String json) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put(uri).contentType(MediaType.APPLICATION_JSON).content(json),
                true);
    }

    private ResultActions delete(String uri) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.delete(uri), true);
    }
}
