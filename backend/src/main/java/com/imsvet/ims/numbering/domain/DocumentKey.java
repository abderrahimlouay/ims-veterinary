package com.imsvet.ims.numbering.domain;

/** ADR-097: the numbered document kinds; the sales keys come from ADR-122. */
public enum DocumentKey {
    /** Purchase order, numbered at creation. */
    PO,
    /** Goods receipt, numbered at confirmation. */
    GR,
    /** Supplier return, numbered at confirmation. */
    SR,
    /** Quote, numbered at confirmation. */
    QUO,
    /** Delivery note, numbered at confirmation. */
    DN,
    /** Invoice, numbered at confirmation. */
    INV,
    /** Receipt, numbered at confirmation. */
    RCT,
    /** Credit note, numbered at confirmation (from Phase 8). */
    CN
}
