package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.finance.domain.SupplierInvoice;
import com.imsvet.ims.finance.infrastructure.SupplierCreditAllocationRepository;
import com.imsvet.ims.finance.infrastructure.SupplierPaymentAllocationRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is left on a supplier document (ADR-132, ADR-133): on an invoice, TTC − credit-note applications − allocations
 * of payments in force; on a credit note, TTC − its applications; never below zero; 0 once cancelled. The caller checks
 * its permission.
 */
@Service
@InternalService
public class SupplierOpenAmounts {

    private static final int MONEY_SCALE = 2;

    private final SupplierCreditAllocationRepository applications;
    private final SupplierPaymentAllocationRepository payments;

    public SupplierOpenAmounts(SupplierCreditAllocationRepository applications,
            SupplierPaymentAllocationRepository payments) {
        this.applications = applications;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public BigDecimal open(SupplierInvoice invoice) {
        if (!invoice.recorded()) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        BigDecimal settled = invoice.creditNote() ? applications.appliedFrom(invoice.id())
                : applications.creditedTo(invoice.id()).add(payments.paidOn(invoice.id()));
        BigDecimal left = invoice.totalTtc().subtract(settled);
        return (left.signum() > 0 ? left : BigDecimal.ZERO).setScale(MONEY_SCALE);
    }
}
