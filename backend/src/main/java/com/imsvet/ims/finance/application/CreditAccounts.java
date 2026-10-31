package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.finance.infrastructure.CustomerPaymentRepository;
import com.imsvet.ims.sales.application.CreditDocuments;
import com.imsvet.ims.sales.application.CreditDocuments.CreditTotals;
import com.imsvet.ims.sales.application.CreditDocuments.DueInvoice;
import com.imsvet.ims.sales.application.CreditLimitPort;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer balance, derived and never stored (BR-PAY-004, ADR-118): confirmed credit invoices − their credit notes −
 * account payments; counter payments never count. The credit check (BR-PAY-005): 0 = no credit sale; an invoice overdue
 * by more than {@code credit_overdue_block_days} blocks when that setting is on, also without a limit (ADR-108); then
 * {@code balance + uninvoiced credit delivery notes + TTC ≤ limit} (ADR-117). The error carries amounts only (ADR-114).
 */
@Service
@InternalService
public class CreditAccounts implements CreditLimitPort {

    private static final int MONEY_SCALE = 2;
    private static final long NO_DOCUMENT = -1L;
    private static final String OFF = "off";

    private final CreditDocuments documents;
    private final CustomerPaymentRepository payments;
    private final SettingsProvider settings;
    private final BusinessCalendar calendar;

    public CreditAccounts(CreditDocuments documents, CustomerPaymentRepository payments, SettingsProvider settings,
            BusinessCalendar calendar) {
        this.documents = documents;
        this.payments = payments;
        this.settings = settings;
        this.calendar = calendar;
    }

    @Override
    @Transactional(readOnly = true)
    public CreditPosition position(long customerId) {
        CreditTotals totals = documents.totals(customerId);
        List<Long> credit = documents.creditDocumentIds(customerId);
        BigDecimal paid = payments.accountPayments(customerId, credit.isEmpty() ? List.of(NO_DOCUMENT) : credit);
        return new CreditPosition(money(totals.invoiced().subtract(totals.creditNoted()).subtract(paid)),
                money(totals.uninvoicedDeliveryNotes()));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireWithinLimit(long customerId, BigDecimal limit, BigDecimal ttc) {
        if (limit != null && limit.signum() == 0) {
            throw new BusinessException(ErrorCode.CREDIT_LIMIT_EXCEEDED, "This customer buys for cash only.",
                    Map.of("limit", money(limit).toPlainString(), "total", ttc.toPlainString()));
        }
        requireNothingOverdue(customerId);
        if (limit == null) {
            return;
        }
        CreditPosition position = position(customerId);
        BigDecimal exposure = position.balance().add(position.uninvoicedDeliveryNotes()).add(ttc);
        if (exposure.compareTo(limit) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_LIMIT_EXCEEDED, "This sale would exceed the credit limit.",
                    Map.of("limit", money(limit).toPlainString(), "balance", position.balance().toPlainString(),
                            "uninvoicedDeliveryNotes", position.uninvoicedDeliveryNotes().toPlainString(),
                            "total", ttc.toPlainString()));
        }
    }

    private void requireNothingOverdue(long customerId) {
        String days = settings.value(SettingDefinition.CREDIT_OVERDUE_BLOCK_DAYS);
        if (OFF.equals(days)) {
            return;
        }
        for (DueInvoice invoice : documents.dueBefore(customerId, calendar.today().minusDays(Long.parseLong(days)))) {
            if (invoice.open().compareTo(payments.allocatedTo(invoice.id())) > 0) {
                throw new BusinessException(ErrorCode.CREDIT_LIMIT_EXCEEDED, "This customer has an overdue invoice.",
                        Map.of("overdueInvoice", invoice.number(), "overdueBlockDays", Integer.parseInt(days)));
            }
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
