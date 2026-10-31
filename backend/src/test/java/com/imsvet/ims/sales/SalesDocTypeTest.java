package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.sales.domain.SalesDocType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BR-SALE-001: the allowed derivations, exactly; ADR-122: the numbering keys. */
class SalesDocTypeTest {

    @Test
    void onlyTheDocumentedDerivationsAreAllowed() {
        Set<SalesDocType> toDelivery = EnumSet.of(SalesDocType.QUOTE);
        Set<SalesDocType> toInvoice = EnumSet.of(SalesDocType.QUOTE, SalesDocType.DELIVERY_NOTE);
        Set<SalesDocType> toCredit = EnumSet.of(SalesDocType.INVOICE, SalesDocType.RECEIPT);
        for (SalesDocType parent : SalesDocType.values()) {
            assertThat(SalesDocType.DELIVERY_NOTE.canDeriveFrom(parent)).isEqualTo(toDelivery.contains(parent));
            assertThat(SalesDocType.INVOICE.canDeriveFrom(parent)).isEqualTo(toInvoice.contains(parent));
            assertThat(SalesDocType.CREDIT_NOTE.canDeriveFrom(parent)).isEqualTo(toCredit.contains(parent));
            assertThat(SalesDocType.QUOTE.canDeriveFrom(parent)).isFalse();
            assertThat(SalesDocType.RECEIPT.canDeriveFrom(parent)).isFalse();
        }
    }

    @Test
    void eachTypeHasItsNumberingKey() {
        assertThat(SalesDocType.QUOTE.key()).isEqualTo(DocumentKey.QUO);
        assertThat(SalesDocType.DELIVERY_NOTE.key()).isEqualTo(DocumentKey.DN);
        assertThat(SalesDocType.INVOICE.key()).isEqualTo(DocumentKey.INV);
        assertThat(SalesDocType.RECEIPT.key()).isEqualTo(DocumentKey.RCT);
        assertThat(SalesDocType.CREDIT_NOTE.key()).isEqualTo(DocumentKey.CN);
    }
}
