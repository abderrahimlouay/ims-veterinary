package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test (M) of the catalogue reference lists (database.md §3.2): seed exactly as ADR-052 with the
 * codes of ADR-054; names unique case- and accent-insensitively (ADR-051).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CatalogReferenceSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void speciesAreSeededWithTheirCodes() {
        assertThat(seed("animal_species")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "BOVINE", "Bovine", "OVINE", "Ovine", "CAPRINE", "Caprine", "POULTRY", "Poultry",
                "EQUINE", "Equine", "CANINE", "Canine", "FELINE", "Feline"));
    }

    @Test
    void therapeuticCategoriesAreSeededWithTheirCodes() {
        assertThat(seed("therapeutic_categories")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ANTIBIOTIC", "Antibiotic", "ANTIPARASITIC", "Antiparasitic", "VACCINE", "Vaccine",
                "ANTI_INFLAMMATORY", "Anti-inflammatory", "VITAMIN_SUPPLEMENT", "Vitamin / Supplement"));
    }

    @Test
    void dosageFormsAreSeededWithTheirCodes() {
        assertThat(seed("dosage_forms")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "INJECTABLE", "Injectable", "POWDER", "Powder", "TABLET", "Tablet", "SOLUTION", "Solution",
                "POUR_ON", "Pour-on"));
    }

    @Test
    void manufacturersAndActiveIngredientsStartEmpty() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_ingredients", Integer.class)).isZero();
    }

    @Test
    void namesAreUniqueIgnoringCaseAndAccents() {
        jdbc.update("INSERT INTO manufacturers (name) VALUES ('Vétoquinol')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO manufacturers (name) VALUES ('VETOQUINOL')"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO animal_species (name) VALUES ('bovine')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private Map<String, String> seed(String table) {
        // The table names are constants of this test, never input.
        return jdbc.queryForList("SELECT code, name FROM " + table + " WHERE code IS NOT NULL").stream()
                .collect(Collectors.toMap(r -> (String) r.get("code"), r -> (String) r.get("name")));
    }
}
