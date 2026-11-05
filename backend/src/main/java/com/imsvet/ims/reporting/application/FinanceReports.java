package com.imsvet.ims.reporting.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.reporting.infrastructure.FinanceFigures;
import com.imsvet.ims.reporting.infrastructure.FinanceFigures.ReceivableItem;
import com.imsvet.ims.reporting.infrastructure.FinanceFigures.SupplierItem;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receivables ageing and supplier payables (ADR-148, BR-RPT-004), as of today (business date); cash-session variances
 * over a period (BR-RPT-006).
 * <p>
 * Customers: the open amount of each credit invoice is aged by days past its due date into "not yet due" and the
 * columns of {@code ageing_buckets}; advances and invoices credited beyond their total lower the balance apart; the
 * balance is that of ADR-118 (Σ columns − advance); uninvoiced credit delivery notes are shown apart, not aged.
 * Suppliers: open invoices by due date, with the unapplied credit notes and advances; balance as BR-PAY-014.
 */
@Service
public class FinanceReports {

    private static final int MONEY_SCALE = 2;
    private static final Set<String> CUSTOMER_TYPES = Set.of("breeder", "veterinarian", "other");

    /** {@code overdue}: one amount per ageing column, the last one beyond the largest limit. */
    public record Ageing(BigDecimal notDue, List<BigDecimal> overdue, BigDecimal advance, BigDecimal balance,
            BigDecimal uninvoicedDeliveryNotes) {
    }

    public record CustomerBalanceRow(long customerId, String name, String phone, String wilaya, BigDecimal notDue,
            List<BigDecimal> overdue, BigDecimal advance, BigDecimal balance, BigDecimal uninvoicedDeliveryNotes) {
    }

    /** {@code bucketDays}: the limits of the overdue columns ({@code 1–30, 31–60, 61–90, > 90} by default). */
    public record CustomerBalancesReport(LocalDate date, String customerType, List<Integer> bucketDays,
            List<CustomerBalanceRow> rows, Ageing totals) {
    }

    public record PayableRow(long invoiceId, long supplierId, String supplierName, String invoiceNumber,
            LocalDate invoiceDate, LocalDate dueDate, long daysOverdue, BigDecimal totalTtc, BigDecimal open) {
    }

    /** {@code balance} = open invoices − unapplied credit notes − advances: what is owed. */
    public record PayablesReport(LocalDate date, Long supplierId, List<PayableRow> rows, BigDecimal totalOpen,
            BigDecimal totalOverdue, BigDecimal unappliedCreditNotes, BigDecimal advances, BigDecimal balance) {
    }

    /** {@code variance} = counted − expected (BR-CASH-002): negative a shortage, positive a surplus. */
    public record CashSessionRow(long sessionId, long cashierId, String cashierName, Long closedById,
            String closedByName, Instant openedAt, Instant closedAt, BigDecimal openingAmount, BigDecimal expectedAmount,
            BigDecimal countedAmount, BigDecimal variance) {
    }

    /** {@code totalShortage} is the sum of the negative variances (≤ 0), {@code totalSurplus} of the positive ones. */
    public record CashSessionsReport(LocalDate from, LocalDate to, Long cashierId, List<CashSessionRow> rows,
            long sessions, long sessionsWithVariance, BigDecimal totalShortage, BigDecimal totalSurplus,
            BigDecimal totalVariance) {
    }

    private final FinanceFigures figures;
    private final SettingsProvider settings;
    private final BusinessCalendar calendar;

    public FinanceReports(FinanceFigures figures, SettingsProvider settings, BusinessCalendar calendar) {
        this.figures = figures;
        this.settings = settings;
        this.calendar = calendar;
    }

    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public CustomerBalancesReport customerBalances(String customerType) {
        String type = customerType == null || customerType.isBlank() ? null : customerType;
        if (type != null && !CUSTOMER_TYPES.contains(type)) {
            throw new ValidationException("Unknown customer type.",
                    List.of(new Violation("customerType", "UNKNOWN", "breeder, veterinarian or other.")));
        }
        List<Integer> limits = Arrays.stream(settings.value(SettingDefinition.AGEING_BUCKETS).split(","))
                .map(Integer::parseInt).toList();
        LocalDate today = calendar.today();
        Map<Long, Accumulator> customers = new LinkedHashMap<>();
        Accumulator totals = new Accumulator(null, limits.size());
        for (ReceivableItem item : figures.receivables(type)) {
            Accumulator customer = customers.computeIfAbsent(item.customerId(),
                    id -> new Accumulator(item, limits.size()));
            customer.add(item, today, limits);
            totals.add(item, today, limits);
        }
        List<CustomerBalanceRow> rows = customers.values().stream().filter(Accumulator::anything)
                .map(Accumulator::row)
                .sorted(Comparator.comparing(CustomerBalanceRow::balance).reversed()
                        .thenComparing(CustomerBalanceRow::name).thenComparing(CustomerBalanceRow::customerId))
                .toList();
        return new CustomerBalancesReport(today, type, limits, rows, totals.ageing());
    }

    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public PayablesReport supplierPayables(Long supplierId) {
        LocalDate today = calendar.today();
        List<PayableRow> rows = new ArrayList<>();
        BigDecimal open = zero();
        BigDecimal overdue = zero();
        BigDecimal credits = zero();
        BigDecimal advances = zero();
        for (SupplierItem item : figures.supplierItems(supplierId)) {
            switch (item.type()) {
                case "invoice" -> {
                    long late = Math.max(0, ChronoUnit.DAYS.between(item.dueDate(), today));
                    rows.add(new PayableRow(item.itemId(), item.supplierId(), item.supplierName(), item.number(),
                            item.invoiceDate(), item.dueDate(), late, money(item.totalTtc()), money(item.open())));
                    open = open.add(item.open());
                    if (late > 0) {
                        overdue = overdue.add(item.open());
                    }
                }
                case "credit_note" -> credits = credits.add(item.open());
                default -> advances = advances.add(item.open());
            }
        }
        return new PayablesReport(today, supplierId, rows, money(open), money(overdue), money(credits),
                money(advances), money(open.subtract(credits).subtract(advances)));
    }

    /** The sessions closed in the period (ADR-148 item 6), by closing time; one cashier or all. */
    @PreAuthorize("hasAuthority('report.finance')")
    @Transactional(readOnly = true)
    public CashSessionsReport cashSessions(LocalDate from, LocalDate to, Long cashierId) {
        ReportPeriod period = ReportPeriod.of(from, to, calendar);
        List<CashSessionRow> rows = figures.closedSessions(period.start(), period.end(), cashierId).stream()
                .map(f -> new CashSessionRow(f.sessionId(), f.cashierId(), f.cashierName(), f.closedBy(),
                        f.closerName(), f.openedAt(), f.closedAt(), money(f.openingAmount()),
                        money(f.expectedAmount()), money(f.countedAmount()), money(f.variance())))
                .toList();
        BigDecimal shortage = zero();
        BigDecimal surplus = zero();
        long withVariance = 0;
        for (CashSessionRow row : rows) {
            if (row.variance().signum() < 0) {
                shortage = shortage.add(row.variance());
            } else if (row.variance().signum() > 0) {
                surplus = surplus.add(row.variance());
            }
            withVariance += row.variance().signum() == 0 ? 0 : 1;
        }
        return new CashSessionsReport(period.from(), period.to(), cashierId, rows, rows.size(), withVariance, shortage,
                surplus, shortage.add(surplus));
    }

    /** One customer's items, or all of them for the totals ({@code first} {@code null}). */
    private static final class Accumulator {

        private final ReceivableItem first;
        private BigDecimal notDue = zero();
        private final BigDecimal[] overdue;
        private BigDecimal advance = zero();
        private BigDecimal deliveryNotes = zero();

        Accumulator(ReceivableItem first, int limits) {
            this.first = first;
            this.overdue = new BigDecimal[limits + 1];
            Arrays.fill(overdue, zero());
        }

        void add(ReceivableItem item, LocalDate today, List<Integer> limits) {
            switch (item.type()) {
                case "invoice" -> {
                    if (item.amount().signum() < 0) {
                        advance = advance.subtract(item.amount());
                    } else {
                        long late = item.dueDate() == null ? 0 : ChronoUnit.DAYS.between(item.dueDate(), today);
                        if (late <= 0) {
                            notDue = notDue.add(item.amount());
                        } else {
                            int column = 0;
                            while (column < limits.size() && late > limits.get(column)) {
                                column++;
                            }
                            overdue[column] = overdue[column].add(item.amount());
                        }
                    }
                }
                case "advance" -> advance = advance.add(item.amount());
                default -> deliveryNotes = deliveryNotes.add(item.amount());
            }
        }

        boolean anything() {
            Ageing ageing = ageing();
            return ageing.balance().signum() != 0 || ageing.advance().signum() != 0
                    || ageing.uninvoicedDeliveryNotes().signum() != 0;
        }

        Ageing ageing() {
            BigDecimal aged = Arrays.stream(overdue).reduce(notDue, BigDecimal::add);
            return new Ageing(money(notDue), Arrays.stream(overdue).map(FinanceReports::money).toList(),
                    money(advance), money(aged.subtract(advance)), money(deliveryNotes));
        }

        CustomerBalanceRow row() {
            Ageing ageing = ageing();
            return new CustomerBalanceRow(first.customerId(), first.customerName(), first.phone(), first.wilaya(),
                    ageing.notDue(), ageing.overdue(), ageing.advance(), ageing.balance(),
                    ageing.uninvoicedDeliveryNotes());
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
