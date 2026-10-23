package com.imsvet.ims.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * architecture.md §3 rule 2 and its documented exception (ADR-090): a module's SQL and JPQL text never names
 * another module's tables or entities, except the one named port implementation that hands the catalog its stock
 * conditions ({@code inventory/application/ProductStockConditions}). Checked on the string literals of the sources;
 * URL paths (literals starting with {@code /}) are not queries.
 */
class ModuleSqlBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/imsvet/ims");

    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);

    /** Inventory names the catalog must never write in its queries. */
    private static final Pattern INVENTORY_NAMES = Pattern.compile(
            "\\b(lots|stock_movements|inventories|inventory_lines|reorder_proposals|integrity_check_runs)\\b"
                    + "|\\bFROM Lot\\b|\\bJOIN Lot\\b|\\bStockMovement\\b");

    /** Catalog names inventory may write only in the named port implementation. */
    private static final Pattern CATALOG_NAMES = Pattern.compile(
            "\\b(products|product_units|product_barcodes|product_active_ingredients|product_target_species"
                    + "|product_withdrawal_periods|therapeutic_categories|manufacturers|dosage_forms|active_ingredients"
                    + "|animal_species)\\b|\\bFROM Product\\b|\\bJOIN Product\\b|\\bp\\.(minStock|maxStock|reorderPoint"
                    + "|archived|commercialName|sku)\\b");

    private static final Set<String> ALLOWED_IN_INVENTORY = Set.of("inventory/application/ProductStockConditions.java");

    @Test
    void theCatalogNeverNamesInventoryTables() throws IOException {
        assertThat(offending(MAIN.resolve("catalog"), INVENTORY_NAMES, Set.of())).isEmpty();
    }

    @Test
    void inventoryNamesCatalogTablesOnlyInTheNamedPortImplementation() throws IOException {
        assertThat(offending(MAIN.resolve("inventory"), CATALOG_NAMES, ALLOWED_IN_INVENTORY)).isEmpty();
    }

    @Test
    void theNamedPortImplementationIsWhereTheExceptionLives() throws IOException {
        Path allowed = MAIN.resolve("inventory/application/ProductStockConditions.java");

        assertThat(literals(allowed)).anySatisfy(text -> assertThat(CATALOG_NAMES.matcher(text).find()).isTrue());
    }

    private static List<String> offending(Path module, Pattern names, Set<String> allowed) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(module)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (allowed.contains(relative)) {
                    continue;
                }
                for (String text : literals(file)) {
                    if (text.startsWith("/")) {
                        continue;
                    }
                    Matcher m = names.matcher(text);
                    if (m.find()) {
                        found.add(relative + ": " + m.group());
                    }
                }
            }
        }
        return found;
    }

    private static List<String> literals(Path file) throws IOException {
        List<String> texts = new ArrayList<>();
        Matcher m = LITERAL.matcher(Files.readString(file));
        while (m.find()) {
            texts.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return texts;
    }
}
