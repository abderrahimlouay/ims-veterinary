package com.imsvet.ims.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The company profile (ADR-140, api.md §4.14): versions only added, the current one read by any user, a new one by
 * {@code settings.manage} with a PNG or JPEG logo of at most 256 KB kept or replaced, audited without the logo's
 * bytes; the table refuses updates and deletes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CompanyProfilesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 4, 5};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient admin;
    private ApiClient seller;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser administrator = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.71");
        admin.login(administrator.username(), administrator.password()).andExpect(status().isOk());
        TestUser counter = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.72");
        seller.login(counter.username(), counter.password()).andExpect(status().isOk());
    }

    @Test
    void aVersionIsAddedReadByEveryoneAndAuditedWithoutTheLogoBytes() throws Exception {
        long before = count();
        if (before == 0) {
            seller.get("/api/company-profile").andExpect(status().isNoContent());
            seller.get("/api/company-profile/logo").andExpect(status().isNotFound());
        }
        long id = id(post(admin, Map.of("legalName", "  Pharmacie Vétérinaire Démo  ", "nif", "000016001234567",
                "nis", "000116010012345", "rc", "16/00-1234567B12", "ai", "16012345678", "email", "contact@demo.test"),
                new MockMultipartFile("logo", "logo.png", "image/png", PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalName").value("Pharmacie Vétérinaire Démo"))
                .andExpect(jsonPath("$.nis").value("000116010012345"))
                .andExpect(jsonPath("$.legalForm").isEmpty())
                .andExpect(jsonPath("$.hasLogo").value(true)));

        seller.get("/api/company-profile").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.rc").value("16/00-1234567B12"));
        seller.get("/api/company-profile/logo").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andExpect(content().bytes(PNG));
        String audited = jdbc.queryForObject("""
                SELECT new_values FROM audit_log WHERE action = 'SETTING_CHANGE' AND entity = 'company_profiles'
                  AND entity_id = ?""", String.class, id);
        assertThat(audited).contains("\"logo\": \"image/png\"").contains("Pharmacie Vétérinaire Démo")
                .doesNotContain(Arrays.toString(PNG));
        assertThat(count()).isEqualTo(before + 1);
    }

    @Test
    void aNewVersionKeepsOrDropsTheLogoAndNeverChangesTheOldOne() throws Exception {
        long first = id(post(admin, Map.of("legalName", "Pharmacie Démo A"),
                new MockMultipartFile("logo", "logo.jpg", "image/jpeg", JPEG)).andExpect(status().isCreated()));
        long kept = id(post(admin, Map.of("legalName", "Pharmacie Démo B", "keepLogo", "true"), null)
                .andExpect(jsonPath("$.hasLogo").value(true)));
        seller.get("/api/company-profile/logo").andExpect(header().string("Content-Type", "image/jpeg"));
        long dropped = id(post(admin, Map.of("legalName", "Pharmacie Démo C"), null)
                .andExpect(jsonPath("$.hasLogo").value(false)));

        assertThat(kept).isGreaterThan(first);
        seller.get("/api/company-profile").andExpect(jsonPath("$.id").value(dropped))
                .andExpect(jsonPath("$.legalName").value("Pharmacie Démo C"));
        seller.get("/api/company-profile/logo").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT legal_name FROM company_profiles WHERE id = ?", String.class, first))
                .isEqualTo("Pharmacie Démo A");
        String old = jdbc.queryForObject("""
                SELECT old_values FROM audit_log WHERE entity = 'company_profiles' AND entity_id = ?""", String.class,
                dropped);
        assertThat(old).contains("Pharmacie Démo B").contains("\"logo\": \"image/jpeg\"");
    }

    @Test
    void invalidVersionsAreRefusedOnTheirField() throws Exception {
        post(admin, Map.of("legalName", " "), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("legalName"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        post(admin, Map.of("legalName", "Pharmacie Démo", "email", "pas-un-mail"), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("EMAIL"));
        post(admin, Map.of("legalName", "Pharmacie Démo", "nif", "x".repeat(41)), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SIZE"));
        post(admin, Map.of("legalName", "Pharmacie Démo"),
                new MockMultipartFile("logo", "logo.gif", "image/png", new byte[] {'G', 'I', 'F', '8'}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("logo"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        byte[] large = Arrays.copyOf(PNG, 256 * 1024 + 1);
        post(admin, Map.of("legalName", "Pharmacie Démo"), new MockMultipartFile("logo", "big.png", "image/png", large))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SIZE"));
    }

    @Test
    void onlySettingsManageAddsAVersion() throws Exception {
        post(seller, Map.of("legalName", "Pharmacie Démo"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void theTableRefusesChangesAndChecksItsLogo() throws Exception {
        long id = id(post(admin, Map.of("legalName", "Pharmacie Démo"), null).andExpect(status().isCreated()));
        // The application account holds SELECT and INSERT only; the triggers are checked as root by the upgrade test.
        assertThatThrownBy(() -> jdbc.update("UPDATE company_profiles SET legal_name = 'X' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("command denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM company_profiles WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("command denied");
        long user = jdbc.queryForObject("SELECT created_by FROM company_profiles WHERE id = ?", Long.class, id);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profiles (legal_name, logo, logo_type, effective_from, created_by)
                VALUES ('X', x'01', NULL, UTC_TIMESTAMP(6), ?)""", user))
                .hasMessageContaining("ck_company_profiles_logo");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profiles (legal_name, logo, logo_type, effective_from, created_by)
                VALUES ('X', x'01', 'image/gif', UTC_TIMESTAMP(6), ?)""", user))
                .hasMessageContaining("ck_company_profiles_logo_type");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profiles (legal_name, effective_from, created_by) VALUES (' ', UTC_TIMESTAMP(6), ?)""",
                user)).hasMessageContaining("ck_company_profiles_legal_name");
    }

    private static ResultActions post(ApiClient client, Map<String, String> fields, MockMultipartFile logo)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/api/company-profile");
        fields.forEach(request::param);
        if (logo != null) {
            request.file(logo);
        }
        return client.perform(request, true);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM company_profiles", Long.class);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
