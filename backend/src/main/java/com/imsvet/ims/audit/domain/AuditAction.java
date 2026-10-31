package com.imsvet.ims.audit.domain;

/**
 * Audited action codes, identical to the catalogue of security.md §7 (BR-AUDIT-001).
 */
public enum AuditAction {
    PRICE_CHANGE,
    INVOICE_CANCEL,
    STOCK_CORRECTION,
    OPENING_STOCK,
    LOT_BLOCK,
    LOT_UNBLOCK,
    LOT_RECALL,
    RETURN_CREATE,
    PO_CANCEL,
    PO_CLOSE,
    RECEIPT_CANCEL,
    PAYMENT_REVERSE,
    CREDIT_LIMIT_CHANGE,
    USER_CREATE,
    USER_UPDATE,
    USER_DEACTIVATE,
    ROLE_PERMISSION_CHANGE,
    PASSWORD_RESET,
    SETTING_CHANGE,
    BACKUP_RUN,
    BACKUP_DOWNLOAD,
    FEFO_OVERRIDE,
    DISCOUNT_OVERRIDE,
    CASH_VARIANCE
}
