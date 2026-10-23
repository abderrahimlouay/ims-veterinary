package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * FR-PROD-004 over the API: active ingredients of a product (with strength) and substitutes sharing an
 * ingredient (without the stock filter until Phase 4); ADR-053 for a used ingredient.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductIngredientsTest {

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
    private long amoxicillin;
    private long clavulanate;
    private long ivermectin;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.140");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        amoxicillin = idOf(pharmacist.post("/api/active-ingredients", "{\"name\":\"Amoxicilline\"}"));
        clavulanate = idOf(pharmacist.post("/api/active-ingredients", "{\"name\":\"Acide clavulanique\"}"));
        ivermectin = idOf(pharmacist.post("/api/active-ingredients", "{\"name\":\"Ivermectine\"}"));
    }

    @Test
    void replacesTheIngredientSetAndShowsItOnTheProduct() throws Exception {
        long product = product("I-1", "Amoxi-clav");

        replace(product, "[{\"ingredientId\":" + amoxicillin + ",\"strength\":\"500 mg\"},"
                + "{\"ingredientId\":" + clavulanate + ",\"strength\":null}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].ingredient.name").value("Acide clavulanique"))
                .andExpect(jsonPath("$[1].strength").value("500 mg"));
        replace(product, "[{\"ingredientId\":" + ivermectin + "}]").andExpect(jsonPath("$", hasSize(1)));

        pharmacist.get("/api/products/" + product).andExpect(jsonPath("$.ingredients", hasSize(1)))
                .andExpect(jsonPath("$.ingredients[0].ingredient.name").value("Ivermectine"));
    }

    @Test
    void duplicateOrUnknownIngredientsAreFieldErrors() throws Exception {
        long product = product("I-2", "Invalid");

        replace(product, "[{\"ingredientId\":" + amoxicillin + "},{\"ingredientId\":" + amoxicillin + "}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ingredients"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        replace(product, "[{\"ingredientId\":999999}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        replace(product, "[{\"ingredientId\":" + amoxicillin + ",\"strength\":\"" + "x".repeat(61) + "\"}]")
                .andExpect(status().isBadRequest());
    }

    /** From Phase 4 only products with sellable stock are proposed (ADR-089); every candidate here has some. */
    @Test
    void substitutesShareAnIngredientMostSharedFirstAndExcludeArchivedProducts() throws Exception {
        long original = product("S-0", "Original");
        long both = product("S-1", "Zeta both");
        long one = product("S-2", "Alpha one");
        long archived = product("S-3", "Archived");
        long unrelated = product("S-4", "Unrelated");
        String amoxiClav = "[{\"ingredientId\":" + amoxicillin + "},{\"ingredientId\":" + clavulanate + "}]";
        replace(original, amoxiClav);
        replace(both, amoxiClav);
        replace(one, "[{\"ingredientId\":" + clavulanate + "}]");
        replace(archived, amoxiClav);
        replace(unrelated, "[{\"ingredientId\":" + ivermectin + "}]");
        pharmacist.post("/api/products/" + archived + "/archive", "").andExpect(status().isOk());
        TestLots lots = new TestLots(jdbc);
        for (long product : new long[] { both, one, archived, unrelated }) {
            lots.lot(product, "SUB-" + product, LocalDate.of(2027, 6, 30), "5");
        }
        long empty = product("S-5", "Empty both");
        replace(empty, amoxiClav);

        pharmacist.get("/api/products/" + original + "/substitutes").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(both))
                .andExpect(jsonPath("$[1].id").value(one));
        pharmacist.get("/api/products/" + unrelated + "/substitutes").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anIngredientUsedByAProductCannotBeDeleted() throws Exception {
        replace(product("D-1", "Uses ingredient"), "[{\"ingredientId\":" + ivermectin + "}]");
        entityManager.flush();
        entityManager.clear();

        pharmacist.perform(MockMvcRequestBuilders.delete("/api/active-ingredients/" + ivermectin), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void theDatabaseRefusesTheSameIngredientTwice() throws Exception {
        long product = product("D-2", "Twice");
        replace(product, "[{\"ingredientId\":" + ivermectin + "}]");
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product_active_ingredients (product_id, ingredient_id) VALUES (?, ?)", product, ivermectin))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_active_ingredients WHERE product_id = ?",
                Integer.class, product)).isEqualTo(1);
    }

    private long product(String sku, String name) throws Exception {
        return idOf(pharmacist.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + name
                + "\",\"baseUnit\":\"ml\",\"divisible\":true}"));
    }

    private ResultActions replace(long product, String json) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + product + "/ingredients")
                .contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
