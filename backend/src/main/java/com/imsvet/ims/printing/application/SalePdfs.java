package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.sales.application.PrintableSales;
import com.imsvet.ims.sales.application.PrintableSales.SalePrint;
import com.imsvet.ims.sales.application.SalesViews.GlobalDiscountView;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleLine;
import com.imsvet.ims.sales.application.SalesViews.SaleWarning;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sales document as a PDF (api.md `/api/sales/{id}/pdf`, ADR-138, ADR-139, ADR-141, ADR-142, ADR-143): A4 or an
 * 80 mm ticket; the company identity in force at its issue; the customer's legal numbers; VAT by rate; the amount in
 * words on an invoice or a credit note (of the net to pay when there is a stamp duty, ADR-172); the stamp duty and
 * the net to pay; the withdrawal warnings for any customer; "ANNULÉ" on a cancelled document; a
 * draft refused. Nothing is written or stored.
 */
@Service
public class SalePdfs {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The PDF and the file name it is served under. */
    public record Pdf(byte[] bytes, String fileName) {
    }

    private static final Map<String, String> TITLES = Map.of("quote", "DEVIS", "delivery_note", "BON DE LIVRAISON",
            "invoice", "FACTURE", "receipt", "TICKET DE CAISSE", "credit_note", "AVOIR");
    /** Seeded species by code (ADR-054, as the screens); a species added later prints its stored name. */
    private static final Map<String, String> SPECIES = Map.of("BOVINE", "Bovin", "OVINE", "Ovin", "CAPRINE", "Caprin",
            "POULTRY", "Volaille", "EQUINE", "Équin", "CANINE", "Canin", "FELINE", "Félin");

    private final PrintableSales sales;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;

    public SalePdfs(PrintableSales sales, CompanyIdentity identity, PdfRenderer renderer, BusinessSettings business) {
        this.sales = sales;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
    }

    @PreAuthorize("hasAuthority('sale.read')")
    @Transactional(readOnly = true)
    public Pdf render(long id, String format) {
        return document(id, paper(format));
    }

    /** The credit note of a customer return, in A4 (`/api/returns/customer/{id}/pdf`, ADR-143). */
    @PreAuthorize("hasAuthority('return.read')")
    @Transactional(readOnly = true)
    public Pdf renderReturn(long returnId) {
        return document(sales.creditNoteOfReturn(returnId), Paper.A4);
    }

    private Pdf document(long id, Paper paper) {
        SalePrint print = sales.of(id);
        SaleDetail d = print.detail();
        if ("draft".equals(d.status())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "A draft is not printed.",
                    Map.of("status", d.status()));
        }
        Map<String, Object> model = model(print);
        byte[] bytes = renderer.render(paper == Paper.A4 ? "sale-a4" : "sale-ticket", model, paper);
        return new Pdf(bytes, d.number() + (paper == Paper.TICKET ? "-ticket" : "") + ".pdf");
    }

    private static Paper paper(String format) {
        if (format == null || format.isBlank() || "a4".equals(format)) {
            return Paper.A4;
        }
        if ("ticket".equals(format)) {
            return Paper.TICKET;
        }
        throw new ValidationException("Unknown format.", List.of(new Violation("format", "UNKNOWN", "a4 or ticket.")));
    }

    private Map<String, Object> model(SalePrint print) {
        SaleDetail d = print.detail();
        ZoneId zone = business.timeZone();
        String currency = PrintFormats.symbol(business.currency());
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.at(d.issuedAt()).orElse(null)));

        Map<String, Object> doc = new HashMap<>();
        doc.put("title", TITLES.get(d.docType()));
        doc.put("number", d.number());
        doc.put("issuedAt", PrintFormats.dateTime(d.issuedAt(), zone));
        doc.put("dueDate", d.creditSale() ? PrintFormats.date(d.dueDate()) : null);
        doc.put("parentNumber", print.parentNumber());
        doc.put("parentLabel", "credit_note".equals(d.docType()) ? "Avoir sur" : "Référence :");
        doc.put("cancelled", "cancelled".equals(d.status()));
        doc.put("cancelReason", d.cancelReason());
        doc.put("notes", d.notes());
        doc.put("creditSale", d.creditSale());
        model.put("doc", doc);
        model.put("customer", PrintFormats.customer(print.customer()));

        List<Map<String, Object>> lines = new ArrayList<>();
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        Map<Long, String> names = new HashMap<>();
        for (SaleLine l : d.lines()) {
            names.put(l.productId(), l.productName());
            Map<String, Object> line = new HashMap<>();
            line.put("product", l.productName());
            line.put("lot", l.lotNumber());
            line.put("expiry", PrintFormats.date(l.expiryDate()));
            line.put("quantity", PrintFormats.quantity(l.quantity()) + (l.unitName() == null ? "" : " " + l.unitName()));
            line.put("unitPrice", PrintFormats.money(l.unitPrice()));
            line.put("discount", l.discountPct() == null || l.discountPct().signum() == 0 ? "—"
                    : PrintFormats.percent(l.discountPct()));
            line.put("vatRate", PrintFormats.percent(l.vatRate()));
            BigDecimal beforeGlobal = l.lineHt().add(l.globalDiscountHt());
            line.put("ht", PrintFormats.money(beforeGlobal));
            line.put("ttc", PrintFormats.money(l.globalDiscountHt().signum() == 0 ? l.lineTtc()
                    : beforeGlobal.add(beforeGlobal.multiply(l.vatRate()).divide(HUNDRED)
                            .setScale(2, RoundingMode.HALF_UP))));
            lines.add(line);
            BigDecimal[] sums = byRate.computeIfAbsent(l.vatRate().stripTrailingZeros(),
                    r -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(l.lineHt());
            sums[1] = sums[1].add(l.lineVat());
        }
        model.put("lines", lines);
        model.put("vatByRate", byRate.entrySet().stream().map(e -> Map.of("rate", PrintFormats.percent(e.getKey()),
                "base", PrintFormats.money(e.getValue()[0]), "vat", PrintFormats.money(e.getValue()[1]))).toList());

        Map<String, Object> totals = new HashMap<>();
        totals.put("ht", PrintFormats.money(d.totals().ht(), currency));
        GlobalDiscountView global = d.totals().globalDiscount();
        boolean discounted = global != null && global.ht().signum() > 0;
        totals.put("htBeforeGlobal", discounted ? PrintFormats.money(d.totals().ht().add(global.ht()), currency) : null);
        totals.put("globalLabel", discounted ? globalLabel(global) : null);
        totals.put("globalHt", discounted ? "-" + PrintFormats.money(global.ht(), currency) : null);
        totals.put("globalTtc", discounted ? "-" + PrintFormats.money(global.ttc(), currency) : null);
        totals.put("ttcBeforeGlobal", discounted ? PrintFormats.money(d.totals().ttc().add(global.ttc()), currency)
                : null);
        totals.put("discount", d.totals().discount() == null || d.totals().discount().signum() == 0 ? null
                : PrintFormats.money(d.totals().discount(), currency));
        totals.put("vat", PrintFormats.money(d.totals().vat(), currency));
        totals.put("ttc", PrintFormats.money(d.totals().ttc(), currency));
        boolean stamped = d.totals().stampDuty().signum() > 0;
        totals.put("stampDuty", stamped ? PrintFormats.money(d.totals().stampDuty(), currency) : null);
        totals.put("netToPay", stamped ? PrintFormats.money(d.totals().netToPay(), currency) : null);
        model.put("totals", totals);
        model.put("words", words(d));
        model.put("payments", d.payments().stream().map(p -> PrintFormats.method(p.method()) + " : "
                + PrintFormats.money(p.amount(), currency) + (p.reference() == null ? "" : " (" + p.reference() + ")"))
                .toList());
        model.put("warnings", "credit_note".equals(d.docType()) ? List.of()
                : print.withdrawals().stream().map(w -> warning(w, names)).toList());
        return model;
    }

    /** ADR-175: "Remise globale (2 %)" for a percentage, "Remise globale" for an amount. */
    private static String globalLabel(GlobalDiscountView global) {
        return "percent".equals(global.kind()) ? "Remise globale (" + PrintFormats.percent(global.value()) + ")"
                : "Remise globale";
    }

    private static String words(SaleDetail d) {
        String amount = AmountInWords.dinars(d.totals().netToPay());
        return switch (d.docType()) {
            case "invoice" -> "Arrêtée la présente facture à la somme de : " + amount + ".";
            case "credit_note" -> "Arrêté le présent avoir à la somme de : " + amount + ".";
            default -> null;
        };
    }


    /** "Produit — Bovins : viande 28 j, lait 7 j" (ADR-142). */
    private static String warning(SaleWarning w, Map<Long, String> names) {
        List<String> periods = new ArrayList<>();
        if (w.meatDays() != null) {
            periods.add("viande " + w.meatDays() + " j");
        }
        if (w.milkDays() != null) {
            periods.add("lait " + w.milkDays() + " j");
        }
        if (w.eggsDays() != null) {
            periods.add("œufs " + w.eggsDays() + " j");
        }
        String species = w.speciesCode() == null ? w.speciesName()
                : SPECIES.getOrDefault(w.speciesCode(), w.speciesName());
        return names.get(w.productId()) + " — " + species + " : " + String.join(", ", periods);
    }
}
