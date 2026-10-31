package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.infrastructure.AnimalSpeciesRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.common.security.InternalService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port for the sales lines: the withdrawal periods of a product (FR-PROD-005). The caller checks its permission. */
@Service
@InternalService
public class ProductWithdrawals {

    public record Period(int speciesId, String speciesCode, String speciesName, Short meatDays, Short milkDays,
            Short eggsDays) {
    }

    private final ProductRepository products;
    private final AnimalSpeciesRepository species;

    public ProductWithdrawals(ProductRepository products, AnimalSpeciesRepository species) {
        this.products = products;
        this.species = species;
    }

    @Transactional(readOnly = true)
    public List<Period> of(long productId) {
        return products.findById(productId).orElseThrow().withdrawalPeriods().stream()
                .map(p -> new Period(p.species().id(), p.species().code(), p.species().name(), p.meatDays(),
                        p.milkDays(), p.eggsDays()))
                .toList();
    }

    public boolean speciesExists(int id) {
        return species.findById(id).isPresent();
    }
}
