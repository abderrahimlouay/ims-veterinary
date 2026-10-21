package com.imsvet.ims.catalog;

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
import com.imsvet.ims.catalog.application.ProductCatalog;
import com.imsvet.ims.catalog.application.ProductCatalog.ProductInput;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.infrastructure.DosageFormRepository;
import com.imsvet.ims.catalog.infrastructure.ManufacturerRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.TherapeuticCategoryRepository;
import com.imsvet.ims.settings.application.SettingsProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BR-PROD-006 "a failed audit aborts the change": the audit failure propagates and the product is never
 * saved; with the recorder joining the caller's transaction (AuditTransactionTest), nothing is committed.
 */
class PriceAuditFailureTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aFailedPriceAuditPreventsTheSave() {
        ProductRepository products = mock(ProductRepository.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Product product = new Product(new Product.Core("SKU", "Name", null, null, null, "ml", true,
                new BigDecimal("9.00"), BigDecimal.ZERO.setScale(3), null, BigDecimal.ZERO.setScale(3)), Instant.EPOCH);
        when(products.findById(5L)).thenReturn(Optional.of(product));
        when(products.findBySku(anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("audit_log unavailable"))
                .when(audit).record(any(), anyString(), any(), anyMap(), anyMap());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "pharmacist", null, List.of(new SimpleGrantedAuthority("price.edit"))));
        ProductCatalog catalog = new ProductCatalog(products, mock(TherapeuticCategoryRepository.class),
                mock(DosageFormRepository.class), mock(ManufacturerRepository.class), mock(SettingsProvider.class),
                audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> catalog.update(5, new ProductInput("SKU", "Name", null, null, null, "ml", true,
                new BigDecimal("9"), null, null, null, null, new BigDecimal("2")), 0))
                .hasMessage("audit_log unavailable");

        verify(products, never()).saveAndFlush(any());
    }
}
