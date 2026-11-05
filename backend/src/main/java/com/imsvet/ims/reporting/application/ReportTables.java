package com.imsvet.ims.reporting.application;

import com.imsvet.ims.reporting.application.FinanceReports.CashSessionRow;
import com.imsvet.ims.reporting.application.FinanceReports.CashSessionsReport;
import com.imsvet.ims.reporting.application.FinanceReports.CustomerBalanceRow;
import com.imsvet.ims.reporting.application.FinanceReports.CustomerBalancesReport;
import com.imsvet.ims.reporting.application.FinanceReports.PayableRow;
import com.imsvet.ims.reporting.application.FinanceReports.PayablesReport;
import com.imsvet.ims.reporting.application.ReportTable.SectionBuilder;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The export layout of each report (ADR-149): French headers and labels, the rows of the JSON report, then its totals.
 * Shared by the CSV and the PDF so that both show the same figures. Takes a report already read under its permission.
 */
@Component
public class ReportTables {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NO_CATEGORY = "Sans catégorie";
    private static final String COUNTER_SALE = "Vente comptoir";
    private static final String TOTAL = "Total";
    private static final int DAY_KEY = 10;

    private static final Map<String, String> GROUPS = Map.of("day", "par jour", "month", "par mois", "category",
            "par catégorie", "product", "par produit", "customer", "par client", "wilaya", "par wilaya", "commune",
            "par commune");
    private static final Map<String, String> CUSTOMER_TYPES = Map.of("breeder", "Éleveurs", "veterinarian",
            "Vétérinaires", "other", "Autres");
    private static final Map<String, String> LOT_STATUSES = Map.of("active", "Actif", "blocked", "Bloqué",
            "recalled", "Rappelé");
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

    public ReportTable revenue(RevenueReport r) {
        SectionBuilder s = new SectionBuilder(null);
        switch (r.groupBy()) {
            case "product" -> {
                s.text("Code", "Produit").number("CA HT");
                r.rows().forEach(x -> s.row(x.code(), x.label(), x.revenueHt()));
                s.total(TOTAL, null, r.totalHt());
                s.total("Bons de livraison non facturés (" + r.uninvoicedDeliveryNotes().count() + ")", null,
                        r.uninvoicedDeliveryNotes().totalHt());
            }
            case "category" -> {
                s.text("Catégorie").number("CA HT");
                r.rows().forEach(x -> s.row(category(x.label()), x.revenueHt()));
                s.total(TOTAL, r.totalHt());
                s.total(uninvoiced(r), r.uninvoicedDeliveryNotes().totalHt());
            }
            default -> {
                s.text(dateHeader(r.groupBy())).number("CA HT");
                r.rows().forEach(x -> s.row(dateKey(x.key()), x.revenueHt()));
                s.total(TOTAL, r.totalHt());
                s.total(uninvoiced(r), r.uninvoicedDeliveryNotes().totalHt());
            }
        }
        return new ReportTable("Chiffre d'affaires", ReportTable.fileName("chiffre-affaires", r.from(), r.to()), false,
                List.of(period(r.from(), r.to()), "Regroupement : " + GROUPS.get(r.groupBy()),
                        "Factures et tickets nets des avoirs, montants HT"), List.of(s.build()));
    }

    public ReportTable margin(MarginReport r) {
        boolean product = "product".equals(r.groupBy());
        SectionBuilder s = new SectionBuilder(null);
        if (product) {
            s.text("Code", "Produit").number("Quantité", "CA HT", "Coût", "Marge", "Taux de marge (%)");
        } else {
            s.text("Catégorie").number("CA HT", "Coût", "Marge", "Taux de marge (%)");
        }
        for (MarginRow x : r.rows()) {
            if (product) {
                s.row(x.code(), x.label(), x.quantityBase(), x.revenueHt(), x.cost(), x.margin(), x.marginRate());
            } else {
                s.row(category(x.label()), x.revenueHt(), x.cost(), x.margin(), x.marginRate());
            }
        }
        if (product) {
            s.total(TOTAL, null, null, r.totalRevenueHt(), r.totalCost(), r.totalMargin(), r.marginRate());
        } else {
            s.total(TOTAL, r.totalRevenueHt(), r.totalCost(), r.totalMargin(), r.marginRate());
        }
        return new ReportTable("Marge brute", ReportTable.fileName("marge", r.from(), r.to()), product,
                List.of(period(r.from(), r.to()), "Regroupement : " + GROUPS.get(r.groupBy()),
                        "Coût : coût unitaire du lot au moment de la vente"), List.of(s.build()));
    }

    public ReportTable statistics(StatisticsReport r) {
        SectionBuilder s = new SectionBuilder(null);
        switch (r.groupBy()) {
            case "customer" -> {
                s.text("Client").number("Documents", "CA HT");
                for (StatisticRow x : r.rows()) {
                    s.row(x.key() == null ? COUNTER_SALE : x.label(), x.documents(), x.revenueHt());
                }
                s.total(TOTAL, r.totalDocuments(), r.totalHt());
            }
            case "wilaya" -> {
                s.text("Wilaya").number("Documents", "CA HT");
                r.rows().forEach(x -> s.row(x.label(), x.documents(), x.revenueHt()));
                s.total(TOTAL, r.totalDocuments(), r.totalHt());
            }
            case "commune" -> {
                s.text("Wilaya", "Commune").number("Documents", "CA HT");
                r.rows().forEach(x -> s.row(x.code(), x.label(), x.documents(), x.revenueHt()));
                s.total(TOTAL, null, r.totalDocuments(), r.totalHt());
            }
            default -> {
                s.text(dateHeader(r.groupBy())).number("Documents", "CA HT");
                r.rows().forEach(x -> s.row(dateKey(x.key()), x.documents(), x.revenueHt()));
                s.total(TOTAL, r.totalDocuments(), r.totalHt());
            }
        }
        List<String> filters = new ArrayList<>(List.of(period(r.from(), r.to()),
                "Regroupement : " + GROUPS.get(r.groupBy())));
        filters.add("Clients : " + (r.customerType() == null ? "tous" : CUSTOMER_TYPES.get(r.customerType())));
        return new ReportTable("Statistiques des ventes",
                ReportTable.fileName("statistiques-ventes", r.from(), r.to()), false, filters, List.of(s.build()));
    }

    public ReportTable valuation(ValuationReport r) {
        boolean product = "product".equals(r.groupBy());
        String basis = "cost".equals(r.basis()) ? "au coût" : "au prix de vente";
        SectionBuilder s = new SectionBuilder(null);
        if (product) {
            s.text("Code", "Produit", "Unité").number("Quantité", "Valeur " + basis, "Quantité invendable",
                    "Valeur invendable");
            for (ValuationRow x : r.rows()) {
                s.row(x.code(), x.label(), x.baseUnit(), x.quantityBase(), x.value(), x.unsellableQuantityBase(),
                        x.unsellableValue());
            }
            s.total(TOTAL, null, null, null, r.totalValue(), null, r.totalUnsellableValue());
        } else {
            s.text("Catégorie").number("Valeur " + basis, "Valeur invendable");
            r.rows().forEach(x -> s.row(category(x.label()), x.value(), x.unsellableValue()));
            s.total(TOTAL, r.totalValue(), r.totalUnsellableValue());
        }
        return new ReportTable("Valorisation du stock", ReportTable.fileName("valorisation-stock", r.date(), null),
                product, List.of("Stock au " + DATE.format(r.date()), "Valorisation " + basis,
                        "Regroupement : " + GROUPS.get(r.groupBy()),
                        "Invendable : lots bloqués, rappelés ou périmés"), List.of(s.build()));
    }

    public ReportTable customerBalances(CustomerBalancesReport r) {
        SectionBuilder s = new SectionBuilder(null).text("Client", "Téléphone", "Wilaya").number("Non échu");
        int previous = 0;
        for (int limit : r.bucketDays()) {
            s.number((previous + 1) + "–" + limit + " j");
            previous = limit;
        }
        s.number("> " + previous + " j", "Avance", "Solde", "BL non facturés");
        for (CustomerBalanceRow x : r.rows()) {
            List<Object> cells = new ArrayList<>(List.of(x.name(), blank(x.phone()), blank(x.wilaya()), x.notDue()));
            cells.addAll(x.overdue());
            cells.addAll(List.of(x.advance(), x.balance(), x.uninvoicedDeliveryNotes()));
            s.row(cells.toArray());
        }
        List<Object> total = new ArrayList<>(List.of(TOTAL, "", "", r.totals().notDue()));
        total.addAll(r.totals().overdue());
        total.addAll(List.of(r.totals().advance(), r.totals().balance(), r.totals().uninvoicedDeliveryNotes()));
        s.total(total.toArray());
        return new ReportTable("Créances clients par ancienneté",
                ReportTable.fileName("creances-clients", r.date(), null), true,
                List.of("Situation au " + DATE.format(r.date()),
                        "Clients : " + (r.customerType() == null ? "tous" : CUSTOMER_TYPES.get(r.customerType())),
                        "Ancienneté : jours de retard après l'échéance; BL non facturés à part"), List.of(s.build()));
    }

    public ReportTable supplierPayables(PayablesReport r) {
        SectionBuilder s = new SectionBuilder(null).text("Fournisseur", "Facture", "Date", "Échéance")
                .number("Jours de retard", "Total TTC", "Reste dû");
        for (PayableRow x : r.rows()) {
            s.row(x.supplierName(), x.invoiceNumber(), x.invoiceDate(), x.dueDate(), x.daysOverdue(), x.totalTtc(),
                    x.open());
        }
        s.total(TOTAL, null, null, null, null, null, r.totalOpen())
                .total("Dont échu", null, null, null, null, null, r.totalOverdue())
                .total("Avoirs non imputés", null, null, null, null, null, r.unappliedCreditNotes())
                .total("Avances", null, null, null, null, null, r.advances())
                .total("Solde", null, null, null, null, null, r.balance());
        List<String> filters = new ArrayList<>(List.of("Situation au " + DATE.format(r.date())));
        if (r.supplierId() != null) {
            filters.add("Fournisseur n° " + r.supplierId());
        }
        return new ReportTable("Dettes fournisseurs par échéance",
                ReportTable.fileName("dettes-fournisseurs", r.date(), null), true, filters, List.of(s.build()));
    }

    public ReportTable cashSessions(CashSessionsReport r) {
        SectionBuilder s = new SectionBuilder(null).number("Session").text("Caissier", "Ouverture", "Clôture",
                "Clôturée par").number("Fonds initial", "Attendu", "Compté", "Écart");
        for (CashSessionRow x : r.rows()) {
            s.row(x.sessionId(), x.cashierName(), x.openedAt(), x.closedAt(), x.closedByName(), x.openingAmount(),
                    x.expectedAmount(), x.countedAmount(), x.variance());
        }
        s.total("Manquants", null, null, null, null, null, null, null, r.totalShortage())
                .total("Excédents", null, null, null, null, null, null, null, r.totalSurplus())
                .total("Écart net", null, null, null, null, null, null, null, r.totalVariance());
        List<String> filters = new ArrayList<>(List.of(period(r.from(), r.to()) + " (date de clôture)"));
        if (r.cashierId() != null) {
            filters.add("Caissier n° " + r.cashierId());
        }
        filters.add(r.sessions() + " session(s) clôturée(s), " + r.sessionsWithVariance() + " avec écart");
        return new ReportTable("Écarts de caisse", ReportTable.fileName("ecarts-caisse", r.from(), r.to()), true,
                filters, List.of(s.build()));
    }

    public ReportTable lotTraceability(LotTraceability r) {
        SectionBuilder lot = new SectionBuilder("Lot").text("Lot", "Code", "Produit", "Péremption", "Statut")
                .number("Stock")
                .row(r.lot().lotNumber(), r.lot().product().sku(), r.lot().product().name(), r.lot().expiryDate(),
                        LOT_STATUSES.getOrDefault(r.lot().status(), r.lot().status())
                                + (r.lot().expired() ? " (périmé)" : ""), r.lot().quantityOnHand());
        SectionBuilder movements = new SectionBuilder("Mouvements").text("Date", "Mouvement").number("Quantité")
                .text("Document", "Numéro", "Tiers", "Utilisateur");
        for (MovementRow x : r.movements()) {
            movements.row(x.at(), MOVEMENTS.getOrDefault(x.type(), x.type()), x.quantity(),
                    x.document() == null ? null : DOCUMENTS.getOrDefault(x.document().type(), x.document().type()),
                    x.document() == null ? null : x.document().number(), x.party() == null ? null : x.party().name(),
                    x.user());
        }
        SectionBuilder suppliers = new SectionBuilder("Amont : fournisseurs").text("Fournisseur")
                .number("Reçu", "Retourné");
        for (SupplierRow x : r.suppliers()) {
            suppliers.row(x.name(), x.received(), x.returned());
        }
        SectionBuilder customers = new SectionBuilder("Aval : clients").text("Client")
                .number("Vendu", "Retourné", "Net");
        for (CustomerRow x : r.customers()) {
            customers.row(x.id() == null ? COUNTER_SALE : x.name(), x.sold(), x.returned(), x.net());
        }
        return new ReportTable("Traçabilité du lot " + r.lot().lotNumber(),
                ReportTable.fileName("tracabilite-lot-" + r.lot().lotNumber(), null, null), true,
                List.of("Quantités en unité de base (" + r.lot().product().baseUnit() + ")"),
                List.of(lot.build(), movements.build(), suppliers.build(), customers.build()));
    }

    private static String uninvoiced(RevenueReport r) {
        return "Bons de livraison non facturés (" + r.uninvoicedDeliveryNotes().count() + ")";
    }

    private static String period(LocalDate from, LocalDate to) {
        return "Période : du " + DATE.format(from) + " au " + DATE.format(to);
    }

    private static String dateHeader(String groupBy) {
        return "day".equals(groupBy) ? "Date" : "Mois";
    }

    /** {@code 2026-11-04} → the date; {@code 2026-11} → {@code 11/2026}. */
    private static Object dateKey(String key) {
        return key.length() == DAY_KEY ? LocalDate.parse(key) : key.substring(5) + "/" + key.substring(0, 4);
    }

    private static String category(String label) {
        return label == null ? NO_CATEGORY : label;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
