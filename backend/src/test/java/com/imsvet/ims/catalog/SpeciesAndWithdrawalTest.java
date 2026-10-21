package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-047 target species and FR-PROD-005 withdrawal periods; BR-PROD-007: one record per species, days ≥ 0,
 * a missing value means "not specified" and stays distinct from 0.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SpeciesAndWithdrawalTest {

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
    private EntityManager entityManager;

    private ApiClient pharmacist;
    private long product;
    private long bovine;
    private long ovine;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.150");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        product = idOf(pharmacist.post("/api/products",
                "{\"sku\":\"WD-1\",\"commercialName\":\"Oxytetra\",\"baseUnit\":\"ml\",\"divisible\":true}"));
        bovine = species("BOVINE");
        ovine = species("OVINE");
    }

    @Test
    void targetSpeciesAreReplacedAsASet() throws Exception {
        put("target-species", "{\"speciesIds\":[" + ovine + "," + bovine + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].code").value("BOVINE"));
        put("target-species", "{\"speciesIds\":[" + ovine + "]}").andExpect(jsonPath("$", hasSize(1)));

        pharmacist.get("/api/products/" + product).andExpect(jsonPath("$.targetSpecies[0].code").value("OVINE"));
        put("target-species", "{\"speciesIds\":[999999]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
    }

    @Test
    void withdrawalPeriodsKeepNotSpecifiedDistinctFromZero() throws Exception {
        put("withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"meatDays\":28,\"milkDays\":0,\"eggsDays\":null},"
                + "{\"speciesId\":" + ovine + ",\"meatDays\":14}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].species.code").value("BOVINE"))
                .andExpect(jsonPath("$[0].meatDays").value(28))
                .andExpect(jsonPath("$[0].milkDays").value(0))
                .andExpect(jsonPath("$[0].eggsDays").doesNotExist());
        entityManager.flush();

        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(IFNULL(milk_days, 'null'), '/', IFNULL(eggs_days, 'null'))
                FROM product_withdrawal_periods WHERE product_id = ? AND species_id = ?""", String.class,
                product, bovine)).isEqualTo("0/null");
        pharmacist.get("/api/products/" + product).andExpect(jsonPath("$.withdrawalPeriods", hasSize(2)));
    }

    @Test
    void oneRecordPerSpeciesAndNoNegativeDays() throws Exception {
        put("withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"meatDays\":1},{\"speciesId\":" + bovine
                + ",\"milkDays\":2}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("withdrawalPeriods"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        put("withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"meatDays\":-1}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        put("withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"milkDays\":40000}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
    }

    @Test
    void theDatabaseEnforcesBrProd007() throws Exception {
        put("withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"meatDays\":1}]").andExpect(status().isOk());
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product_withdrawal_periods (product_id, species_id) VALUES (?, ?)", product, bovine))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product_withdrawal_periods (product_id, species_id, eggs_days) VALUES (?, ?, -1)",
                product, ovine))
                .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(e.getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(3819)));
    }

    @Test
    void aSpeciesUsedByAProductCannotBeDeleted() throws Exception {
        long camel = idOf(pharmacist.post("/api/animal-species", "{\"name\":\"Camelin\"}"));
        put("target-species", "{\"speciesIds\":[" + camel + "]}").andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        pharmacist.perform(MockMvcRequestBuilders.delete("/api/animal-species/" + camel), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    private long species(String code) {
        return jdbc.queryForObject("SELECT id FROM animal_species WHERE code = ?", Long.class, code);
    }

    private ResultActions put(String path, String json) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + product + "/" + path)
                .contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
