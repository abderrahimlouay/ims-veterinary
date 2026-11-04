package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.finance.application.AccountStatements;
import com.imsvet.ims.finance.application.AccountStatements.Statement;
import com.imsvet.ims.finance.application.AccountStatements.StatementLine;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.sales.application.PrintableSales;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's account statement as an A4 PDF (`/api/customers/{id}/statement?format=pdf`, ADR-137, ADR-143): the
 * same statement as the screen for the period asked, the customer's legal numbers (ADR-141) and the current company
 * identity, since a statement is a snapshot of the day it is printed.
 */
@Service
public class StatementPdfs {

    private static final Map<String, String> KINDS = Map.of("invoice", "Facture", "credit_note", "Avoir", "payment",
            "Règlement", "reversal", "Annulation de règlement");

    private final AccountStatements statements;
    private final PrintableSales sales;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;
    private final Clock clock;

    public StatementPdfs(AccountStatements statements, PrintableSales sales, CompanyIdentity identity,
            PdfRenderer renderer, BusinessSettings business, Clock clock) {
        this.statements = statements;
        this.sales = sales;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('statement.read')")
    @Transactional(readOnly = true)
    public Pdf render(long customerId, LocalDate from, LocalDate to) {
        Statement s = statements.statement(customerId, from, to);
        String currency = PrintFormats.symbol(business.currency());
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.current().orElse(null)));
        model.put("customer", PrintFormats.customer(sales.customer(customerId)));
        model.put("printedAt", PrintFormats.dateTime(clock.instant(), business.timeZone()));
        model.put("period", s.from() == null ? "Jusqu'au " + PrintFormats.date(s.to())
                : "Du " + PrintFormats.date(s.from()) + " au " + PrintFormats.date(s.to()));
        model.put("opening", PrintFormats.money(s.openingBalance()));
        model.put("lines", s.lines().stream().map(StatementPdfs::line).toList());
        model.put("totalDebit", PrintFormats.money(s.totalDebit()));
        model.put("totalCredit", PrintFormats.money(s.totalCredit()));
        model.put("closing", PrintFormats.money(s.closingBalance(), currency));
        model.put("uninvoiced", s.uninvoicedDeliveryNotes().signum() == 0 ? null
                : PrintFormats.money(s.uninvoicedDeliveryNotes(), currency));
        return new Pdf(renderer.render("statement-a4", model, Paper.A4), "releve-client-" + customerId + ".pdf");
    }

    private static Map<String, Object> line(StatementLine l) {
        String piece = l.number() != null ? l.number()
                : PrintFormats.joined(" ", PrintFormats.method(l.method()), l.reference());
        return Map.of("date", PrintFormats.date(l.date()), "kind", KINDS.getOrDefault(l.kind(), l.kind()), "piece",
                piece == null ? "—" : piece, "debit", blankIfZero(l.debit()), "credit", blankIfZero(l.credit()),
                "balance", PrintFormats.money(l.balance()));
    }

    private static String blankIfZero(BigDecimal amount) {
        return amount.signum() == 0 ? "" : PrintFormats.money(amount);
    }
}
