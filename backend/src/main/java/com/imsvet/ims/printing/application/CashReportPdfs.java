package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.finance.application.CashReports;
import com.imsvet.ims.finance.application.CashReports.CashReport;
import com.imsvet.ims.finance.application.CashReports.NonCashEntry;
import com.imsvet.ims.finance.application.CashReports.SourceTotal;
import com.imsvet.ims.finance.application.CashSessionView;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A cash-session report as an A4 PDF (`/api/cash-sessions/{id}/report?format=pdf`, ADR-136, ADR-137, ADR-143): the
 * same report as the screen (opening amount, cash by source, expected, counted and variance, then the cheques and
 * transfers apart), the current company identity. Who may read it is decided by {@link CashReports#report}: the
 * cashier for their own session, else {@code cash.manage}. Users are named by id, as on the screen.
 */
@Service
public class CashReportPdfs {

    private static final Map<String, String> SOURCES = Map.of("sales", "Ventes", "account_payments",
            "Règlements sur compte", "refunds", "Remboursements", "supplier_payments", "Paiements fournisseurs",
            "reversals", "Annulations de règlements", "manual", "Mouvements manuels");
    private static final Map<String, String> NON_CASH = Map.of("customer_payment", "Règlement client",
            "customer_refund", "Remboursement client", "supplier_payment", "Paiement fournisseur");

    private final CashReports reports;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;
    private final Clock clock;

    public CashReportPdfs(CashReports reports, CompanyIdentity identity, PdfRenderer renderer,
            BusinessSettings business, Clock clock) {
        this.reports = reports;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyAuthority('cash.open', 'cash.manage')")
    @Transactional(readOnly = true)
    public Pdf render(long sessionId) {
        CashReport r = reports.report(sessionId);
        CashSessionView s = r.session();
        ZoneId zone = business.timeZone();
        String currency = PrintFormats.symbol(business.currency());
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.current().orElse(null)));
        model.put("printedAt", PrintFormats.dateTime(clock.instant(), zone));
        Map<String, Object> session = new HashMap<>();
        session.put("id", s.id());
        session.put("open", "open".equals(s.status()));
        session.put("cashier", user(s.cashierId()));
        session.put("openedAt", PrintFormats.dateTime(s.openedAt(), zone));
        session.put("closedAt", PrintFormats.dateTime(s.closedAt(), zone));
        session.put("closedBy", r.closedBy() == null ? null : user(r.closedBy()));
        model.put("session", session);
        model.put("opening", PrintFormats.money(r.openingAmount(), currency));
        model.put("sources", r.bySource().stream().map(CashReportPdfs::source).toList());
        model.put("expected", PrintFormats.money(r.expected(), currency));
        model.put("counted", r.counted() == null ? "—" : PrintFormats.money(r.counted(), currency));
        model.put("variance", r.variance() == null ? "—" : PrintFormats.money(r.variance(), currency));
        model.put("nonCash", r.nonCash().stream().map(e -> nonCash(e, zone)).toList());
        return new Pdf(renderer.render("cash-report-a4", model, Paper.A4), "caisse-session-" + s.id() + ".pdf");
    }

    private static Map<String, Object> source(SourceTotal t) {
        return Map.of("label", SOURCES.getOrDefault(t.source(), t.source()), "in", blankIfZero(t.in()), "out",
                blankIfZero(t.out()));
    }

    private static Map<String, Object> nonCash(NonCashEntry e, ZoneId zone) {
        String kind = NON_CASH.getOrDefault(e.kind(), e.kind()) + (e.reversal() ? " (annulation)" : "");
        return Map.of("date", PrintFormats.dateTime(e.at(), zone), "kind", kind, "method",
                PrintFormats.method(e.method()), "reference", e.reference() == null ? "—" : e.reference(), "in",
                "in".equals(e.direction()) ? PrintFormats.money(e.amount()) : "", "out",
                "out".equals(e.direction()) ? PrintFormats.money(e.amount()) : "");
    }

    private static String user(long id) {
        return "Utilisateur n° " + id;
    }

    private static String blankIfZero(BigDecimal amount) {
        return amount.signum() == 0 ? "" : PrintFormats.money(amount);
    }
}
