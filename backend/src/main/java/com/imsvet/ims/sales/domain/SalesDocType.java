package com.imsvet.ims.sales.domain;

import com.imsvet.ims.numbering.domain.DocumentKey;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-SALE-001: the sales papers, their numbering key (ADR-122) and the derivations allowed between them. */
public enum SalesDocType {
    QUOTE(DocumentKey.QUO), DELIVERY_NOTE(DocumentKey.DN), INVOICE(DocumentKey.INV), RECEIPT(DocumentKey.RCT),
    CREDIT_NOTE(DocumentKey.CN);

    private final DocumentKey key;

    SalesDocType(DocumentKey key) {
        this.key = key;
    }

    public DocumentKey key() {
        return key;
    }

    /** Quote → delivery note or invoice, delivery note → invoice, invoice or receipt → credit note. */
    public boolean canDeriveFrom(SalesDocType parent) {
        return switch (this) {
            case DELIVERY_NOTE -> parent == QUOTE;
            case INVOICE -> parent == QUOTE || parent == DELIVERY_NOTE;
            case CREDIT_NOTE -> parent == INVOICE || parent == RECEIPT;
            case QUOTE, RECEIPT -> false;
        };
    }

    /** A credit note only comes from a customer return (Phase 8). */
    public boolean creatableDirectly() {
        return this != CREDIT_NOTE;
    }

    /** Only a counter receipt may have no customer, and it is never a credit sale (BR-SALE-012). */
    public boolean counterReceipt() {
        return this == RECEIPT;
    }

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SalesDocType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst();
    }
}
