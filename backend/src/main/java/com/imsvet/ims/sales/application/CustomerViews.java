package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.CustomerCategory;
import java.math.BigDecimal;
import java.time.Instant;

/** api.md §4.9 customer DTOs. */
public final class CustomerViews {

    public record CategoryView(int id, String name, BigDecimal discountPct, Instant updatedAt, long version) {
    }

    static CategoryView category(CustomerCategory c) {
        return new CategoryView(c.id(), c.name(), c.discountPct(), c.updatedAt(), c.version());
    }

    private CustomerViews() {
    }
}
