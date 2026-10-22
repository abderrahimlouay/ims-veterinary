package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.catalog.application.ProductUnits;
import com.imsvet.ims.catalog.application.ProductUnits.UnitInput;
import com.imsvet.ims.catalog.application.UnitReferences;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductUnit;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductUnitRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Service rules of packaging units without a database. BR-UNIT-004: a unit referenced by a document line
 * keeps its factor ({@code UNIT_FACTOR_LOCKED}), while its other fields stay editable; document lines exist
 * only from Phase 5, so the "in use" answer is stubbed here. BR-PROD-006: a failed price audit prevents the save.
 */
class ProductUnitRulesTest {

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductUnitRepository units = mock(ProductUnitRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final UnitReferences references = mock(UnitReferences.class);
    private ProductUnit box;

    @BeforeEach
    void unit() {
        Product product = new Product(new Product.Core("SKU", "Name", null, null, null, "tablet", false,
                new BigDecimal("9.00"), BigDecimal.ZERO.setScale(3), null, BigDecimal.ZERO.setScale(3)), Instant.EPOCH);
        box = new ProductUnit(product, "Box", new BigDecimal("30.0000"), new BigDecimal("0.35"), true);
        ReflectionTestUtils.setField(box, "id", 8L);
        when(products.findById(5L)).thenReturn(Optional.of(product));
        when(units.findByIdAndProductId(8L, 5L)).thenReturn(Optional.of(box));
        when(units.findByProductIdAndName(5L, "Box")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "pharmacist", null, List.of(new SimpleGrantedAuthority("price.edit"))));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFactorOfAUnitInUseCannotChange() {
        when(references.referencesUnit(8L)).thenReturn(true);

        assertThatThrownBy(() -> service(List.of(references)).update(5, 8,
                new UnitInput("Box", new BigDecimal("20"), new BigDecimal("0.35"), true), 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNIT_FACTOR_LOCKED));
        verify(units, never()).saveAndFlush(any());
    }

    @Test
    void aUnitInUseKeepsItsOtherFieldsEditable() {
        when(references.referencesUnit(8L)).thenReturn(true);
        when(units.saveAndFlush(box)).thenReturn(box);

        service(List.of(references)).update(5, 8, new UnitInput("Carton", new BigDecimal("30.0"), null, false), 0);

        assertThat(box.name()).isEqualTo("Carton");
        assertThat(box.sellable()).isFalse();
    }

    @Test
    void withoutDocumentLinesNoUnitIsInUse() {
        when(units.saveAndFlush(box)).thenReturn(box);

        service(List.of()).update(5, 8, new UnitInput("Box", new BigDecimal("20"), new BigDecimal("0.35"), true), 0);

        assertThat(box.factorToBase()).isEqualByComparingTo("20");
    }

    @Test
    void aFailedPriceAuditPreventsTheSave() {
        doThrow(new IllegalStateException("audit_log unavailable"))
                .when(audit).record(any(), anyString(), any(), anyMap(), anyMap());

        assertThatThrownBy(() -> service(List.of()).update(5, 8,
                new UnitInput("Box", new BigDecimal("30"), new BigDecimal("0.40"), true), 0))
                .hasMessage("audit_log unavailable");
        verify(units, never()).saveAndFlush(any());
    }

    private ProductUnits service(List<UnitReferences> contributors) {
        return new ProductUnits(products, units, contributors, audit);
    }
}
