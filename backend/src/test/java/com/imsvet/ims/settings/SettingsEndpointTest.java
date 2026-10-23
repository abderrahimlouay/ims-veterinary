package com.imsvet.ims.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * API test (A) of the settings endpoints (FR-SEC-003, api.md §4.14, ADR-044) including the audit of every
 * change (BR-AUDIT-001).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingsEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private BusinessSettings business;

    private TestUser admin;
    private ApiClient client;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        admin = new TestUsers(jdbc, encoder).create("Administrator");
        client = ApiClient.create(context, "198.51.100.70");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());
    }

    @Test
    void listsEverySettingWithItsEditability() throws Exception {
        client.get("/api/settings").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(11)))
                .andExpect(jsonPath("$[?(@.key == 'currency')].editable").value(false))
                .andExpect(jsonPath("$[?(@.key == 'default_vat_rate')].editable").value(true));
        client.get("/api/settings/expiry_alert_days").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("60,30,15"));
    }

    @Test
    void changeIsNormalisedAndAuditedWithTheUser() throws Exception {
        put("default_vat_rate", "19").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("19.00"));

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT user_id, old_values->>'$.value' AS old_value, new_values->>'$.value' AS new_value,
                       new_values->>'$.key' AS setting
                FROM audit_log WHERE action = 'SETTING_CHANGE' AND user_id = ?""", admin.id());
        assertThat(audit).containsEntry("old_value", "9.00").containsEntry("new_value", "19.00")
                .containsEntry("setting", "default_vat_rate");
    }

    @Test
    void unchangedValueIsNotAudited() throws Exception {
        put("default_vat_rate", "9.0").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id = ?", Integer.class,
                admin.id())).isZero();
    }

    @Test
    void invalidValuesAreFieldErrors() throws Exception {
        put("expiry_alert_days", "30,60,15").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
        put("login_max_failures", "0").andExpect(status().isBadRequest());
    }

    @Test
    void currencyAndTimeZoneAreReadOnly() throws Exception {
        put("currency", "EUR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("READ_ONLY"));
        put("business_time_zone", "Europe/Paris").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("READ_ONLY"));
    }

    @Test
    void unknownKeyIsNotFound() throws Exception {
        put("no_such_key", "1").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        client.get("/api/settings/no_such_key").andExpect(status().isNotFound());
    }

    @Test
    void localeChangeAppliesToTheBusinessSettingsAtOnce() throws Exception {
        put("locale", "ar-DZ").andExpect(status().isOk());

        assertThat(business.locale()).isEqualTo(Locale.forLanguageTag("ar-DZ"));
    }

    @Test
    void publicSettingsAreReadableByEveryUserAndHoldNoSecuritySettings() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.71");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isOk());

        String body = sellerClient.get("/api/settings/public").andExpect(status().isOk())
                .andExpect(jsonPath("$.expiryAlertDays").value(org.hamcrest.Matchers.contains(60, 30, 15)))
                .andExpect(jsonPath("$.defaultVatRate").value("9.00"))
                .andExpect(jsonPath("$.currency").value("DZD"))
                .andExpect(jsonPath("$.locale").value("fr-DZ"))
                .andExpect(jsonPath("$.timeZone").value("Africa/Algiers"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("login");
    }

    private ResultActions put(String key, String value) throws Exception {
        return client.perform(MockMvcRequestBuilders.put("/api/settings/" + key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + value + "\"}"), true);
    }
}
