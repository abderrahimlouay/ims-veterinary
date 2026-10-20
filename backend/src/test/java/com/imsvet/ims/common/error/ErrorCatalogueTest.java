package com.imsvet.ims.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The code enum and the catalogue in docs/api.md §3 must stay identical (ADR-020).
 */
class ErrorCatalogueTest {

    private static final Path API_DOC = Path.of("..", "docs", "api.md");
    private static final Pattern ROW = Pattern.compile("^\\| `([A-Z_]+)` \\| (\\d{3}) \\|");

    @Test
    void errorCodesMatchTheDocumentedCatalogue() throws IOException {
        Map<String, Integer> documented = new TreeMap<>();
        boolean inCatalogue = false;
        for (String line : Files.readAllLines(API_DOC)) {
            if (line.startsWith("## ")) {
                inCatalogue = line.startsWith("## 3.");
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (inCatalogue && m.find()) {
                documented.put(m.group(1), Integer.parseInt(m.group(2)));
            }
        }

        Map<String, Integer> implemented = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, c -> c.status().value(), (a, b) -> a, TreeMap::new));

        assertThat(documented).isNotEmpty();
        assertThat(implemented).isEqualTo(documented);
    }
}
