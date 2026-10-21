package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.application.ProductViews.WithdrawalPeriodView;
import com.imsvet.ims.catalog.domain.AnimalSpecies;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.WithdrawalPeriod;
import com.imsvet.ims.catalog.infrastructure.AnimalSpeciesRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Target species (ADR-047) and withdrawal periods (FR-PROD-005, BR-PROD-007) of a product, each replaced as
 * a whole set. Both feed the species filter of the product search.
 */
@Service
public class ProductSpecies {

    /** Days {@code null}: not specified (BR-PROD-007). */
    public record PeriodLine(Integer speciesId, Integer meatDays, Integer milkDays, Integer eggsDays) {
    }

    private final ProductRepository products;
    private final AnimalSpeciesRepository species;

    public ProductSpecies(ProductRepository products, AnimalSpeciesRepository species) {
        this.products = products;
        this.species = species;
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public List<ReferenceItemView> replaceTargetSpecies(long productId, List<Integer> speciesIds) {
        Product product = find(productId);
        List<Violation> violations = new ArrayList<>();
        Set<AnimalSpecies> resolved = new HashSet<>();
        for (Integer id : speciesIds) {
            AnimalSpecies s = resolve(id, "speciesIds", violations);
            if (s != null && !resolved.add(s)) {
                violations.add(new Violation("speciesIds", "DUPLICATE", "A species is listed twice."));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The species list is invalid.", violations);
        }
        product.replaceTargetSpecies(resolved);
        products.flush();
        return ProductViews.targetSpecies(product);
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public List<WithdrawalPeriodView> replaceWithdrawalPeriods(long productId, List<PeriodLine> lines) {
        Product product = find(productId);
        List<Violation> violations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<WithdrawalPeriod> periods = new ArrayList<>();
        for (PeriodLine line : lines) {
            AnimalSpecies s = resolve(line.speciesId(), "withdrawalPeriods", violations);
            if (s == null) {
                continue;
            }
            if (!seen.add(s.id())) {
                violations.add(new Violation("withdrawalPeriods", "DUPLICATE", "A species is listed twice."));
                continue;
            }
            periods.add(new WithdrawalPeriod(s, days(line.meatDays(), violations), days(line.milkDays(), violations),
                    days(line.eggsDays(), violations)));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The withdrawal periods are invalid.", violations);
        }
        product.replaceWithdrawalPeriods(periods);
        products.flush();
        return ProductViews.withdrawalPeriods(product);
    }

    private AnimalSpecies resolve(Integer id, String field, List<Violation> violations) {
        AnimalSpecies s = id == null ? null : species.findById(id).orElse(null);
        if (s == null) {
            violations.add(new Violation(field, "UNKNOWN", "Unknown species."));
        }
        return s;
    }

    /** ≥ 0 and within the SMALLINT column; {@code null} stays {@code null}. */
    private static Short days(Integer days, List<Violation> violations) {
        if (days == null) {
            return null;
        }
        if (days < 0) {
            violations.add(new Violation("withdrawalPeriods", "NOT_NEGATIVE", "Days cannot be negative."));
            return null;
        }
        if (days > Short.MAX_VALUE) {
            violations.add(new Violation("withdrawalPeriods", "RANGE", "Too many days."));
            return null;
        }
        return days.shortValue();
    }

    private Product find(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }
}
