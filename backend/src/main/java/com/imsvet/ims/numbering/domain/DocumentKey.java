package com.imsvet.ims.numbering.domain;

/** ADR-097: the numbered document kinds of Phase 5; sales keys are decided in Phase 7. */
public enum DocumentKey {
    /** Purchase order, numbered at creation. */
    PO,
    /** Goods receipt, numbered at confirmation. */
    GR,
    /** Supplier return, numbered at confirmation. */
    SR
}
