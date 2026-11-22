package com.imsvet.ims.catalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Port defined by the catalog and implemented by {@code purchasing} (ADR-180, as ADR-090): the supplier of a
 * product, a laboratory or a wholesaler, is chosen from the suppliers, which the catalog cannot read itself.
 */
public interface ProductSuppliers {

    /** {@code kind} is {@code laboratory} or {@code wholesaler} (ADR-100). */
    record SupplierOption(long id, String name, String kind, boolean active) {
    }

    /** The suppliers of these ids that exist, by id. */
    Map<Long, SupplierOption> find(Collection<Long> ids);

    /** The active suppliers, by name: the choices of the product form and filter. */
    List<SupplierOption> active();
}
