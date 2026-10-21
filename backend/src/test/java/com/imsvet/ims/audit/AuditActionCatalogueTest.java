package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.audit.domain.AuditAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The action enum and the catalogue in docs/security.md §7 must stay identical (BR-AUDIT-001).
 */
class AuditActionCatalogueTest {

    private static final Path SECURITY_DOC = Path.of("..", "docs", "security.md");
    private static final Pattern CODE = Pattern.compile("`([A-Z_]+)`");

    @Test
    void actionsMatchTheDocumentedCatalogue() throws IOException {
        Set<String> documented = new TreeSet<>();
        boolean inCatalogue = false;
        for (String line : Files.readAllLines(SECURITY_DOC)) {
            if (line.startsWith("## ")) {
                inCatalogue = line.startsWith("## 7.");
                continue;
            }
            if (inCatalogue && line.startsWith("| `")) {
                CODE.matcher(line.split("\\|")[1]).results().forEach(r -> documented.add(r.group(1)));
            }
        }

        Set<String> implemented = Arrays.stream(AuditAction.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(documented).isNotEmpty();
        assertThat(implemented).isEqualTo(documented);
    }
}
