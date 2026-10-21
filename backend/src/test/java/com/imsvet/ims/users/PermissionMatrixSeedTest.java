package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seeded roles, permission codes and role-permission mapping equal the matrix of docs/security.md §4
 * (ADR-037), cell by cell. A cell "● (read)" grants only the row's {@code .read} code.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PermissionMatrixSeedTest {

    private static final Path SECURITY_DOC = Path.of("..", "docs", "security.md");
    private static final List<String> ROLES = List.of("Administrator", "Pharmacist", "Seller", "Accountant");
    private static final Pattern CODE = Pattern.compile("`([a-z_.]+)`");
    private static final Pattern ONLY = Pattern.compile("\\((\\w+)\\)");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rolesAreTheFourOfTheSpec() {
        assertThat(jdbc.queryForList("SELECT name FROM roles", String.class))
                .containsExactlyInAnyOrderElementsOf(ROLES);
    }

    @Test
    void seededCatalogueAndGrantsEqualTheDocumentedMatrix() throws IOException {
        Set<String> documentedCodes = new TreeSet<>();
        Set<String> documentedGrants = new TreeSet<>();
        boolean inMatrix = false;
        for (String line : Files.readAllLines(SECURITY_DOC)) {
            if (line.startsWith("## ")) {
                inMatrix = line.startsWith("## 4.");
                continue;
            }
            if (!inMatrix || !line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.substring(1).split("\\|");
            List<String> codes = CODE.matcher(cells[0]).results().map(r -> r.group(1)).toList();
            documentedCodes.addAll(codes);
            for (int i = 0; i < ROLES.size(); i++) {
                String cell = cells[2 + i].trim();
                if (!cell.startsWith("●")) {
                    assertThat(cell).as("cell %s of %s", ROLES.get(i), codes).isEqualTo("–");
                    continue;
                }
                Matcher only = ONLY.matcher(cell);
                String suffix = only.find() ? "." + only.group(1) : null;
                for (String code : codes) {
                    if (suffix == null || code.endsWith(suffix)) {
                        documentedGrants.add(ROLES.get(i) + " " + code);
                    }
                }
            }
        }

        Set<String> seededCodes = new TreeSet<>(jdbc.queryForList("SELECT code FROM permissions", String.class));
        Set<String> seededGrants = new TreeSet<>(jdbc.queryForList("""
                SELECT CONCAT(r.name, ' ', p.code) FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id""", String.class));

        assertThat(documentedCodes).hasSize(53);
        assertThat(seededCodes).isEqualTo(documentedCodes);
        assertThat(seededGrants).isEqualTo(documentedGrants);
    }

    @Test
    void administratorHoldsBothAdministrationPermissions() {
        assertThat(jdbc.queryForList("""
                SELECT p.code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.name = 'Administrator' AND p.code IN ('user.manage', 'role.manage')""", String.class))
                .containsExactlyInAnyOrder("user.manage", "role.manage");
    }
}
