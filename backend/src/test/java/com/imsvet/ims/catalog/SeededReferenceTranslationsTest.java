package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-054: every seeded reference code has a non-empty label in each UI language file of the frontend
 * (`reference.<list>.<CODE>`), so a missing translation fails the build.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeededReferenceTranslationsTest {

    private static final Path LOCALES = Path.of("..", "frontend", "src", "lib", "i18n", "locales");
    private static final Map<String, String> LISTS = Map.of(
            "animal_species", "species", "therapeutic_categories", "categories", "dosage_forms", "dosageForms");

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"fr", "ar", "en"})
    void everySeededCodeHasALabel(String language) throws IOException {
        JsonNode reference = JsonMapper.builder().build()
                .readTree(LOCALES.resolve(language + ".json").toFile()).path("reference");

        LISTS.forEach((table, key) -> {
            // The table names are constants of this test, never input.
            List<String> codes = jdbc.queryForList("SELECT code FROM " + table + " WHERE code IS NOT NULL",
                    String.class);
            assertThat(codes).isNotEmpty();
            assertThat(codes).allSatisfy(code -> assertThat(reference.path(key).path(code).asString(""))
                    .as("%s label of %s.%s", language, key, code).isNotBlank());
        });
    }
}
