package com.imsvet.ims.numbering.domain;

/**
 * ADR-097: the numbered document kinds; the sales keys come from ADR-122, the customer-return key from ADR-131. The key
 * names the counter (document_sequences.doc_key, unchanged); the printed prefix is French (ADR-174).
 */
public enum DocumentKey {
    /** Purchase order (bon de commande), numbered at creation. */
    PO("BC"),
    /** Goods receipt (bon de réception), numbered at confirmation. */
    GR("BR"),
    /** Supplier return (retour fournisseur), numbered at confirmation. */
    SR("RF"),
    /** Quote (devis), numbered at confirmation. */
    QUO("DEV"),
    /** Delivery note (bon de livraison), numbered at confirmation. */
    DN("BL"),
    /** Invoice (facture), numbered at confirmation. */
    INV("FAC"),
    /** Receipt (ticket de caisse), numbered at confirmation. */
    RCT("TC"),
    /** Credit note (avoir), numbered at confirmation (from Phase 8). */
    CN("AV"),
    /** Customer return (retour client), numbered at creation (ADR-131). */
    CR("RC");

    private final String prefix;

    DocumentKey(String prefix) {
        this.prefix = prefix;
    }

    /** What the printed number starts with (ADR-174). */
    public String prefix() {
        return prefix;
    }
}
