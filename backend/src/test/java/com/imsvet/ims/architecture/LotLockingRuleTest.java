package com.imsvet.ims.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * BR-STOCK-001/002, ADR-012 and the fix of PR #65: lots are locked only through {@code LotLocks}, which locks in
 * ascending id and reloads each lot, so no movement committed by another transaction is lost. Nothing else may take
 * a lock that returns a lot: no {@code @Lock} repository method on {@code Lot}, no {@code EntityManager} lock call
 * outside the lock helpers, no SQL text locking {@code lots}. Sales (Phase 7) rely on this.
 */
class LotLockingRuleTest {

    private static final String ROOT = "com.imsvet.ims";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);

    private static final Pattern LOCKING_LOTS = Pattern.compile(
            "(?is)\\blots\\b.*\\bFOR\\s+(UPDATE|SHARE)\\b|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b");

    @Test
    void onlyTheLockHelpersCallTheEntityManagerWithALockMode() {
        noClasses().that().doNotHaveSimpleName("LotLocks").and().doNotHaveSimpleName("ProposalLocks")
                .and().doNotHaveSimpleName("OrderLocks").and().doNotHaveSimpleName("CustomerLocks")
                .and().doNotHaveSimpleName("CashSessionLocks").and().doNotHaveSimpleName("SalesDocumentLocks")
                .should().callMethod(EntityManager.class, "refresh", Object.class, LockModeType.class)
                .orShould().callMethod(EntityManager.class, "lock", Object.class, LockModeType.class)
                .orShould().callMethod(EntityManager.class, "find", Class.class, Object.class, LockModeType.class)
                .because("lots are locked and reloaded only by LotLocks (PR #65)")
                .check(MAIN);
    }

    /**
     * The other lock helpers are allowed by class name for their own rows only (customers, proposals, orders, cash sessions): none
     * of them may even refer to a lot, so a lock or a query on lots from them fails here.
     */
    @Test
    void theOtherLockHelpersNeverReferToLots() {
        noClasses().that().haveSimpleNameEndingWith("Locks").and().doNotHaveSimpleName("LotLocks")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + ".inventory.domain.Lot")
                .orShould().dependOnClassesThat().haveSimpleName("LotRepository")
                .because("only LotLocks locks lots (PR #65)")
                .check(MAIN);
    }

    @Test
    void noRepositoryMethodLocksALot() {
        List<String> offending = new ArrayList<>();
        for (var javaClass : MAIN) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isAnnotatedWith(Lock.class)) {
                    continue;
                }
                Method reflected = method.reflect();
                String query = reflected.isAnnotationPresent(Query.class) ? reflected.getAnnotation(Query.class).value() : "";
                boolean returnsLots = reflected.getGenericReturnType().getTypeName().matches(".*\\binventory\\.domain\\.Lot\\b.*");
                if (returnsLots || query.matches("(?s).*\\bFROM\\s+Lot\\b.*")) {
                    offending.add(javaClass.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void noSqlTextLocksTheLotsTable() throws IOException {
        List<String> offending = new ArrayList<>();
        Path main = Path.of("src/main/java/com/imsvet/ims");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    String text = m.group(1) != null ? m.group(1) : m.group(2);
                    if (LOCKING_LOTS.matcher(text).find()) {
                        offending.add(main.relativize(file) + ": " + text.strip());
                    }
                }
            }
        }
        assertThat(offending).isEmpty();
    }
}
