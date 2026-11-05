package com.imsvet.ims.reporting.api;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.reporting.application.FinanceReports.CashSessionRow;
import com.imsvet.ims.reporting.application.FinanceReports.CashSessionsReport;
import com.imsvet.ims.reporting.application.FinanceReports.CustomerBalanceRow;
import com.imsvet.ims.reporting.application.FinanceReports.CustomerBalancesReport;
import com.imsvet.ims.reporting.application.FinanceReports.PayableRow;
import com.imsvet.ims.reporting.application.FinanceReports.PayablesReport;
import com.imsvet.ims.reporting.application.SalesReports.MarginReport;
import com.imsvet.ims.reporting.application.SalesReports.MarginRow;
import com.imsvet.ims.reporting.application.SalesReports.RevenueReport;
import com.imsvet.ims.reporting.application.SalesReports.StatisticRow;
import com.imsvet.ims.reporting.application.SalesReports.StatisticsReport;
import com.imsvet.ims.reporting.application.StockReports.ValuationReport;
import com.imsvet.ims.reporting.application.StockReports.ValuationRow;
import com.imsvet.ims.reporting.application.TraceabilityReports.CustomerRow;
import com.imsvet.ims.reporting.application.TraceabilityReports.LotTraceability;
import com.imsvet.ims.reporting.application.TraceabilityReports.MovementRow;
import com.imsvet.ims.reporting.application.TraceabilityReports.SupplierRow;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The CSV exports of the reports (ADR-149, api.md §4.13 {@code format=csv}): the same figures as the JSON report, French
 * headers, one row per report row, then a total row; downloaded as an attachment, never cached.
 */
@Component
class ReportCsv {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String NO_CATEGORY = "Sans catégorie";
    private static final String COUNTER_SALE = "Vente comptoir";
    private static final String TOTAL = "Total";
    private static final int DAY_KEY = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, String> MOVEMENTS = Map.ofEntries(
            Map.entry("purchase_receipt", "Réception"), Map.entry("receipt_cancellation", "Annulation de réception"),
            Map.entry("supplier_return", "Retour fournisseur"), Map.entry("opening_stock", "Stock initial"),
            Map.entry("sale", "Vente"), Map.entry("sale_cancellation", "Annulation de vente"),
            Map.entry("customer_return", "Retour client"), Map.entry("return_discard", "Mise au rebut (retour)"),
            Map.entry("adjustment_breakage", "Casse"), Map.entry("adjustment_expiry", "Péremption"),
            Map.entry("adjustment_theft", "Vol"), Map.entry("adjustment_internal_use", "Usage interne"),
            Map.entry("adjustment_inventory", "Inventaire"));
    private static final Map<String, String> DOCUMENTS = Map.of("goods_receipt", "Bon de réception",
            "supplier_return", "Retour fournisseur", "invoice", "Facture", "receipt", "Ticket de caisse",
            "delivery_note", "Bon de livraison", "credit_note", "Avoir", "customer_return", "Retour client");

    private final BusinessSettings business;

    ReportCsv(BusinessSettings business) {
        this.business = business;
    }

    ResponseEntity<byte[]> revenue(RevenueReport r) {
        Csv csv = csv();
        switch (r.groupBy()) {
            case "product" -> {
                csv.row("Code", "Produit", "CA HT");
                r.rows().forEach(x -> csv.row(x.code(), x.label(), x.revenueHt()));
                csv.row(TOTAL, null, r.totalHt());
            }
            case "category" -> {
                csv.row("Catégorie", "CA HT");
                r.rows().forEach(x -> csv.row(category(x.label()), x.revenueHt()));
                csv.row(TOTAL, r.totalHt());
            }
            default -> {
                csv.row(dateHeader(r.groupBy()), "CA HT");
                r.rows().forEach(x -> csv.row(dateKey(x.key()), x.revenueHt()));
                csv.row(TOTAL, r.totalHt());
            }
        }
        csv.row("Bons de livraison non facturés (" + r.uninvoicedDeliveryNotes().count() + ")",
                r.uninvoicedDeliveryNotes().totalHt());
        return attachment(csv, "chiffre-affaires", r.from(), r.to());
    }

    ResponseEntity<byte[]> margin(MarginReport r) {
        Csv csv = csv();
        boolean product = "product".equals(r.groupBy());
        if (product) {
            csv.row("Code", "Produit", "Quantité", "CA HT", "Coût", "Marge", "Taux de marge (%)");
        } else {
            csv.row("Catégorie", "CA HT", "Coût", "Marge", "Taux de marge (%)");
        }
        for (MarginRow x : r.rows()) {
            if (product) {
                csv.row(x.code(), x.label(), x.quantityBase(), x.revenueHt(), x.cost(), x.margin(), x.marginRate());
            } else {
                csv.row(category(x.label()), x.revenueHt(), x.cost(), x.margin(), x.marginRate());
            }
        }
        if (product) {
            csv.row(TOTAL, null, null, r.totalRevenueHt(), r.totalCost(), r.totalMargin(), r.marginRate());
        } else {
            csv.row(TOTAL, r.totalRevenueHt(), r.totalCost(), r.totalMargin(), r.marginRate());
        }
        return attachment(csv, "marge", r.from(), r.to());
    }

    ResponseEntity<byte[]> statistics(StatisticsReport r) {
        Csv csv = csv();
        switch (r.groupBy()) {
            case "customer" -> {
                csv.row("Client", "Documents", "CA HT");
                for (StatisticRow x : r.rows()) {
                    csv.row(x.key() == null ? COUNTER_SALE : x.label(), x.documents(), x.revenueHt());
                }
                csv.row(TOTAL, r.totalDocuments(), r.totalHt());
            }
            case "wilaya" -> {
                csv.row("Wilaya", "Documents", "CA HT");
                r.rows().forEach(x -> csv.row(x.label(), x.documents(), x.revenueHt()));
                csv.row(TOTAL, r.totalDocuments(), r.totalHt());
            }
            case "commune" -> {
                csv.row("Wilaya", "Commune", "Documents", "CA HT");
                r.rows().forEach(x -> csv.row(x.code(), x.label(), x.documents(), x.revenueHt()));
                csv.row(TOTAL, null, r.totalDocuments(), r.totalHt());
            }
            default -> {
                csv.row(dateHeader(r.groupBy()), "Documents", "CA HT");
                r.rows().forEach(x -> csv.row(dateKey(x.key()), x.documents(), x.revenueHt()));
                csv.row(TOTAL, r.totalDocuments(), r.totalHt());
            }
        }
        return attachment(csv, "statistiques-ventes", r.from(), r.to());
    }

    ResponseEntity<byte[]> valuation(ValuationReport r) {
        Csv csv = csv();
        String basis = "cost".equals(r.basis()) ? "au coût" : "au prix de vente";
        if ("product".equals(r.groupBy())) {
            csv.row("Code", "Produit", "Unité", "Quantité", "Valeur " + basis, "Quantité invendable",
                    "Valeur invendable");
            for (ValuationRow x : r.rows()) {
                csv.row(x.code(), x.label(), x.baseUnit(), x.quantityBase(), x.value(), x.unsellableQuantityBase(),
                        x.unsellableValue());
            }
            csv.row(TOTAL, null, null, null, r.totalValue(), null, r.totalUnsellableValue());
        } else {
            csv.row("Catégorie", "Valeur " + basis, "Valeur invendable");
            r.rows().forEach(x -> csv.row(category(x.label()), x.value(), x.unsellableValue()));
            csv.row(TOTAL, r.totalValue(), r.totalUnsellableValue());
        }
        return attachment(csv, "valorisation-stock", r.date(), null);
    }

    ResponseEntity<byte[]> customerBalances(CustomerBalancesReport r) {
        Csv csv = csv();
        List<Object> header = new ArrayList<>(List.of("Client", "Téléphone", "Wilaya", "Non échu"));
        int previous = 0;
        for (int limit : r.bucketDays()) {
            header.add((previous + 1) + "–" + limit + " j");
            previous = limit;
        }
        header.add("> " + previous + " j");
        header.addAll(List.of("Avance", "Solde", "BL non facturés"));
        csv.row(header.toArray());
        for (CustomerBalanceRow x : r.rows()) {
            List<Object> cells = new ArrayList<>(List.of(x.name(), nullable(x.phone()), nullable(x.wilaya()),
                    x.notDue()));
            cells.addAll(x.overdue());
            cells.addAll(List.of(x.advance(), x.balance(), x.uninvoicedDeliveryNotes()));
            csv.row(cells.toArray());
        }
        List<Object> total = new ArrayList<>(List.of(TOTAL, "", "", r.totals().notDue()));
        total.addAll(r.totals().overdue());
        total.addAll(List.of(r.totals().advance(), r.totals().balance(), r.totals().uninvoicedDeliveryNotes()));
        csv.row(total.toArray());
        return attachment(csv, "creances-clients", r.date(), null);
    }

    ResponseEntity<byte[]> supplierPayables(PayablesReport r) {
        Csv csv = csv().row("Fournisseur", "Facture", "Date", "Échéance", "Jours de retard", "Total TTC", "Reste dû");
        for (PayableRow x : r.rows()) {
            csv.row(x.supplierName(), x.invoiceNumber(), x.invoiceDate(), x.dueDate(), x.daysOverdue(), x.totalTtc(),
                    x.open());
        }
        csv.row(TOTAL, null, null, null, null, null, r.totalOpen())
                .row("Dont échu", null, null, null, null, null, r.totalOverdue())
                .row("Avoirs non imputés", null, null, null, null, null, r.unappliedCreditNotes())
                .row("Avances", null, null, null, null, null, r.advances())
                .row("Solde", null, null, null, null, null, r.balance());
        return attachment(csv, "dettes-fournisseurs", r.date(), null);
    }

    ResponseEntity<byte[]> cashSessions(CashSessionsReport r) {
        Csv csv = csv().row("Session", "Caissier", "Ouverture", "Clôture", "Clôturée par", "Fonds initial",
                "Attendu", "Compté", "Écart");
        for (CashSessionRow x : r.rows()) {
            csv.row(x.sessionId(), x.cashierName(), x.openedAt(), x.closedAt(), x.closedByName(), x.openingAmount(),
                    x.expectedAmount(), x.countedAmount(), x.variance());
        }
        csv.row("Manquants", null, null, null, null, null, null, null, r.totalShortage())
                .row("Excédents", null, null, null, null, null, null, null, r.totalSurplus())
                .row("Écart net", null, null, null, null, null, null, null, r.totalVariance());
        return attachment(csv, "ecarts-caisse", r.from(), r.to());
    }

    ResponseEntity<byte[]> lotTraceability(LotTraceability r) {
        Csv csv = csv().row("Lot", r.lot().lotNumber(), "Produit", r.lot().product().sku(), r.lot().product().name(),
                "Péremption", r.lot().expiryDate(), "Stock", r.lot().quantityOnHand())
                .row()
                .row("Date", "Mouvement", "Quantité", "Document", "Numéro", "Tiers", "Utilisateur");
        for (MovementRow x : r.movements()) {
            csv.row(x.at(), MOVEMENTS.getOrDefault(x.type(), x.type()), x.quantity(),
                    x.document() == null ? null : DOCUMENTS.getOrDefault(x.document().type(), x.document().type()),
                    x.document() == null ? null : x.document().number(),
                    x.party() == null ? null : x.party().name(), x.user());
        }
        csv.row().row("Fournisseur", "Reçu", "Retourné");
        for (SupplierRow x : r.suppliers()) {
            csv.row(x.name(), x.received(), x.returned());
        }
        csv.row().row("Client", "Vendu", "Retourné", "Net");
        for (CustomerRow x : r.customers()) {
            csv.row(x.id() == null ? COUNTER_SALE : x.name(), x.sold(), x.returned(), x.net());
        }
        return attachment(csv, "tracabilite-lot-" + r.lot().lotNumber(), null, null);
    }

    private Csv csv() {
        return new Csv(business.timeZone());
    }

    private static String dateHeader(String groupBy) {
        return "day".equals(groupBy) ? "Date" : "Mois";
    }

    /** {@code 2026-11-04} → {@code 04/11/2026}; {@code 2026-11} → {@code 11/2026}. */
    private static String dateKey(String key) {
        return key.length() == DAY_KEY ? LocalDate.parse(key).format(DATE) : key.substring(5) + "/" + key.substring(0, 4);
    }

    private static String category(String label) {
        return label == null ? NO_CATEGORY : label;
    }

    private static Object nullable(String value) {
        return value == null ? "" : value;
    }

    private static ResponseEntity<byte[]> attachment(Csv csv, String name, LocalDate from, LocalDate to) {
        String file = name.replaceAll("[^A-Za-z0-9._-]", "_") + (from == null ? "" : "-" + from)
                + (to == null ? "" : "-" + to) + ".csv";
        return ResponseEntity.ok().contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build()
                        .toString())
                .cacheControl(CacheControl.noStore()).body(csv.bytes());
    }
}
