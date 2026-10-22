package com.imsvet.ims.catalog;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Barcodes over the API (FR-PROD-003): BR-PROD-002 (unique, exact and case-sensitive, ADR-058), BR-PROD-008
 * (format per type, ADR-067), BR-UNIT-005 (a barcode names a unit of its own product; a scan returns that unit
 * with factor and price and a quantity of 1, ADR-057) and the {@code barcode} filter of the product search.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductBarcodesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient pharmacist;
    private long tablets;
    private long box;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.150");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        tablets = product("BC-TAB");
        box = idOf(pharmacist.post("/api/products/" + tablets + "/units",
                "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}").andExpect(status().isCreated()));
    }

    @Test
    void addsAndListsBarcodesWithTheirUnit() throws Exception {
        add(tablets, "{\"code\":\"4006381333931\",\"codeType\":\"EAN13\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("4006381333931"))
                .andExpect(jsonPath("$.codeType").value("EAN13"))
                .andExpect(jsonPath("$.unit").doesNotExist());
        add(tablets, "{\"code\":\"BOX-AMX\",\"codeType\":\"CODE128\",\"unitId\":" + box + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit.id").value(box))
                .andExpect(jsonPath("$.unit.name").value("Box"));

        pharmacist.get("/api/products/" + tablets + "/barcodes").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("4006381333931"))
                .andExpect(jsonPath("$[1].unit.name").value("Box"));
    }

    @Test
    void aScanReturnsTheProductItsUnitAndAQuantityOfOne() throws Exception {
        add(tablets, "{\"code\":\"BOX-AMX\",\"codeType\":\"CODE128\",\"unitId\":" + box + "}");
        add(tablets, "{\"code\":\"4006381333931\",\"codeType\":\"EAN13\"}");

        pharmacist.get("/api/products/by-barcode/BOX-AMX").andExpect(status().isOk())
                .andExpect(jsonPath("$.product.id").value(tablets))
                .andExpect(jsonPath("$.product.sku").value("BC-TAB"))
                .andExpect(jsonPath("$.unit.name").value("Box"))
                .andExpect(jsonPath("$.unit.factorToBase").value("30.0000"))
                .andExpect(jsonPath("$.unit.effectiveSalePrice").value("0.3750"))
                .andExpect(jsonPath("$.quantity").value("1"));
        pharmacist.get("/api/products/by-barcode/4006381333931").andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").doesNotExist())
                .andExpect(jsonPath("$.quantity").value("1"));
        pharmacist.get("/api/products/by-barcode/box-amx").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidBarcodesAreFieldErrors() throws Exception {
        long other = product("BC-OTHER");
        long otherUnit = idOf(pharmacist.post("/api/products/" + other + "/units",
                "{\"name\":\"Box\",\"factorToBase\":\"10\",\"sellable\":true}"));

        assertFieldError(add(tablets, "{\"code\":\"4006381333932\",\"codeType\":\"EAN13\"}"), "code", "CHECK_DIGIT");
        assertFieldError(add(tablets, "{\"code\":\"400638133393\",\"codeType\":\"EAN13\"}"), "code", "FORMAT");
        assertFieldError(add(tablets, "{\"code\":\"Amoxicilline é\",\"codeType\":\"CODE128\"}"), "code", "FORMAT");
        assertFieldError(add(tablets, "{\"code\":\"" + "x".repeat(65) + "\",\"codeType\":\"QR\"}"), "code", "SIZE");
        assertFieldError(add(tablets, "{\"code\":\" \",\"codeType\":\"QR\"}"), "code", "REQUIRED");
        assertFieldError(add(tablets, "{\"code\":\"X1\",\"codeType\":\"UPC\"}"), "codeType", "UNKNOWN");
        assertFieldError(add(tablets, "{\"code\":\"X1\"}"), "codeType", "REQUIRED");
        assertFieldError(add(tablets, "{\"code\":\"X1\",\"codeType\":\"CODE128\",\"unitId\":" + otherUnit + "}"),
                "unitId", "UNKNOWN");
    }

    @Test
    void aCodeBelongsToOneProductOnlyAndIsCaseSensitive() throws Exception {
        long other = product("BC-OTHER-2");
        add(tablets, "{\"code\":\"abc-1\",\"codeType\":\"CODE128\"}").andExpect(status().isCreated());

        add(other, "{\"code\":\"abc-1\",\"codeType\":\"CODE128\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BARCODE_DUPLICATE"))
                .andExpect(jsonPath("$.context.field").value("code"));
        add(other, "{\"code\":\"ABC-1\",\"codeType\":\"CODE128\"}").andExpect(status().isCreated());
    }

    @Test
    void aBarcodeIsRemovedOnlyThroughItsOwnProduct() throws Exception {
        long other = product("BC-OTHER-3");
        long barcode = idOf(add(tablets, "{\"code\":\"BOX-AMX\",\"codeType\":\"CODE128\",\"unitId\":" + box + "}"));

        delete(other, barcode).andExpect(status().isNotFound());
        delete(tablets, barcode).andExpect(status().isNoContent());

        pharmacist.get("/api/products/" + tablets + "/barcodes").andExpect(jsonPath("$.length()").value(0));
        pharmacist.get("/api/products/by-barcode/BOX-AMX").andExpect(status().isNotFound());
        delete(tablets, barcode).andExpect(status().isNotFound());
    }

    @Test
    void theProductSearchFiltersByExactBarcode() throws Exception {
        product("BC-NOISE");
        add(tablets, "{\"code\":\"AMX/10\",\"codeType\":\"CODE128\",\"unitId\":" + box + "}");

        pharmacist.get("/api/products?barcode=AMX/10").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tablets))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        pharmacist.get("/api/products?barcode=amx/10").andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void unknownProductsAreNotFound() throws Exception {
        pharmacist.get("/api/products/999999/barcodes").andExpect(status().isNotFound());
        add(999999, "{\"code\":\"X1\",\"codeType\":\"QR\"}").andExpect(status().isNotFound());
    }

    private long product(String sku) throws Exception {
        return idOf(pharmacist.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + sku + "\","
                + "\"baseUnit\":\"tablet\",\"divisible\":false,\"salePrice\":\"0.0125\"}").andExpect(status().isCreated()));
    }

    private ResultActions add(long productId, String body) throws Exception {
        return pharmacist.post("/api/products/" + productId + "/barcodes", body);
    }

    private ResultActions delete(long productId, long barcodeId) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.delete("/api/products/" + productId + "/barcodes/" + barcodeId),
                true);
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
