package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductSuppliers;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The catalog's {@link ProductSuppliers} (ADR-180): read inside the catalog's own transaction and permissions. */
@Component
class CatalogSuppliers implements ProductSuppliers {

    private final SupplierRepository suppliers;

    CatalogSuppliers(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    public Map<Long, SupplierOption> find(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return suppliers.findAllByIdIn(ids).stream().map(CatalogSuppliers::option)
                .collect(Collectors.toMap(SupplierOption::id, Function.identity()));
    }

    @Override
    public List<SupplierOption> active() {
        return suppliers.findAllByActiveTrueOrderByNameAscIdAsc().stream().map(CatalogSuppliers::option).toList();
    }

    private static SupplierOption option(Supplier s) {
        return new SupplierOption(s.id(), s.name(), s.kind().code(), s.active());
    }
}
