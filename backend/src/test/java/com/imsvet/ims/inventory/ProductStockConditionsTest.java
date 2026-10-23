package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.imsvet.ims.catalog.application.ProductStock.Condition;
import com.imsvet.ims.catalog.application.ProductStock.StockFilter;
import com.imsvet.ims.inventory.application.ExpiryPolicies;
import com.imsvet.ims.inventory.application.ProductStockConditions;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.LotStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * ADR-090: the conditions inventory gives the catalog are constant JPQL text; every value — the business date from
 * the injected clock, the active status — travels as a bind parameter, so nothing can change the query text.
 */
class ProductStockConditionsTest {

    @Test
    void theTextIsConstantAndTheBusinessDateIsABindParameter() {
        ProductStockConditions today = conditionsOn(LocalDate.of(2026, 10, 20));
        ProductStockConditions later = conditionsOn(LocalDate.of(2031, 1, 1));

        for (StockFilter filter : StockFilter.values()) {
            Condition first = today.condition(filter);
            Condition second = later.condition(filter);

            assertThat(second.jpql()).as(filter.name()).isEqualTo(first.jpql());
            assertThat(first.jpql()).as(filter.name()).doesNotContain("2026", "2031", "'");
            assertThat(first.parameters()).as(filter.name()).containsEntry("stockActiveStatus", LotStatus.ACTIVE)
                    .containsEntry("stockBusinessDate", LocalDate.of(2026, 10, 20));
            assertThat(second.parameters()).containsEntry("stockBusinessDate", LocalDate.of(2031, 1, 1));
        }
    }

    private static ProductStockConditions conditionsOn(LocalDate businessDate) {
        ExpiryPolicies policies = mock(ExpiryPolicies.class);
        when(policies.current()).thenReturn(new ExpiryPolicy(businessDate, 60, 30, 15));
        return new ProductStockConditions(policies, null);
    }
}
