package com.imsvet.ims.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.imsvet.ims.common.security.InternalService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module and layer rules of architecture.md §3 and testing.md §10 (ADR-006, ADR-034).
 */
@AnalyzeClasses(packages = "com.imsvet.ims", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.imsvet.ims.";

    @ArchTest
    static final ArchRule modulesRespectAllowedDependencies = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("common").definedBy(ROOT + "common..")
            .layer("auth").definedBy(ROOT + "auth..")
            .layer("users").definedBy(ROOT + "users..")
            .layer("catalog").definedBy(ROOT + "catalog..")
            .layer("inventory").definedBy(ROOT + "inventory..")
            .layer("purchasing").definedBy(ROOT + "purchasing..")
            .layer("sales").definedBy(ROOT + "sales..")
            .layer("finance").definedBy(ROOT + "finance..")
            .layer("reporting").definedBy(ROOT + "reporting..")
            .layer("audit").definedBy(ROOT + "audit..")
            .layer("numbering").definedBy(ROOT + "numbering..")
            .layer("settings").definedBy(ROOT + "settings..")
            .layer("printing").definedBy(ROOT + "printing..")
            .layer("backup").definedBy(ROOT + "backup..")
            .whereLayer("auth").mayOnlyAccessLayers("common", "users", "settings")
            .whereLayer("users").mayOnlyAccessLayers("common", "audit")
            .whereLayer("catalog").mayOnlyAccessLayers("common", "settings", "audit")
            .whereLayer("numbering").mayOnlyAccessLayers("common")
            .whereLayer("settings").mayOnlyAccessLayers("common", "audit")
            .whereLayer("audit").mayOnlyAccessLayers("common")
            .whereLayer("inventory").mayOnlyAccessLayers("common", "catalog", "numbering", "settings", "audit", "users")
            .whereLayer("purchasing").mayOnlyAccessLayers(
                    "common", "catalog", "inventory", "numbering", "settings", "audit", "users")
            .whereLayer("sales").mayOnlyAccessLayers(
                    "common", "catalog", "inventory", "numbering", "settings", "audit")
            .whereLayer("finance").mayOnlyAccessLayers(
                    "common", "sales", "purchasing", "numbering", "audit", "settings")
            .whereLayer("printing").mayOnlyAccessLayers("common", "purchasing", "sales", "finance")
            .whereLayer("backup").mayOnlyAccessLayers("common", "settings", "audit");

    /**
     * ADR-089: {@code inventory} and {@code purchasing} read user names through {@code UserNames} only, nothing else
     * of {@code users}.
     */
    @ArchTest
    static final ArchRule inventoryUsesOnlyTheUserNamesPort = noClasses()
            .that().resideInAnyPackage(ROOT + "inventory..", ROOT + "purchasing..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + "users..")
                    .and(not(simpleName("UserNames"))));

    /** BR-STOCK-002, ADR-088: only StockService writes movements or changes a lot balance. */
    @ArchTest
    static final ArchRule onlyStockServiceWritesStock = noClasses()
            .that().doNotHaveSimpleName("StockService")
            .should().dependOnClassesThat().haveSimpleName("StockMovementRepository")
            .orShould().callMethodWhere(target(name("applyDelta")).and(target(owner(simpleName("Lot")))));

    @ArchTest
    static final ArchRule commonDependsOnNoModule = noClasses()
            .that().resideInAPackage(ROOT + "common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "auth..", ROOT + "users..", ROOT + "catalog..", ROOT + "inventory..",
                    ROOT + "purchasing..", ROOT + "sales..", ROOT + "finance..", ROOT + "reporting..",
                    ROOT + "audit..", ROOT + "numbering..", ROOT + "settings..", ROOT + "printing..",
                    ROOT + "backup..");

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching(ROOT + "(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule apiLayerDoesNotUseInfrastructure = noClasses()
            .that().resideInAPackage(ROOT + "*.api..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "*.infrastructure..");

    @ArchTest
    static final ArchRule apiLayerDoesNotExposeEntities = noClasses()
            .that().resideInAPackage(ROOT + "*.api..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage(ROOT + "*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "*.api..", ROOT + "*.application..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnApi = noClasses()
            .that().resideInAPackage(ROOT + "*.application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "*.api..");

    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class);

    @ArchTest
    static final ArchRule controllerMethodsAreNotTransactional = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class);

    /** BR-SEC-002: every use case declares its permission; ports for other services are marked instead. */
    @ArchTest
    static final ArchRule applicationServicesDeclareAPermission = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().resideInAPackage(ROOT + "*.application..")
            .and().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
            .and().areDeclaredInClassesThat().areNotAnnotatedWith(InternalService.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .because("security.md §3: permissions are enforced on application-service methods");

    /** security.md §14: no password or hash value in anything returned to a client (flags are fine). */
    @ArchTest
    static final ArchRule responsesCarryNoPasswordData = noFields()
            .that().areDeclaredInClassesThat(simpleNameEndingWith("Response").or(simpleNameEndingWith("View")))
            .and().doNotHaveRawType(boolean.class)
            .should().haveNameMatching("(?i).*(password|hash).*");

    @ArchTest
    static final ArchRule noBinaryFloatingPointFields = noFields()
            .should().haveRawType(double.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Float.class)
            .because("money and quantities use BigDecimal (CLAUDE.md §4)");
}
