package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.inventory.application.LotViews.RecallReport;
import com.imsvet.ims.inventory.application.RecalledLotLabels;
import com.imsvet.ims.inventory.application.RecalledLotLabels.LotLabel;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buyers of a recalled lot as an A4 PDF (`/api/lots/{id}/recall-report?format=pdf`, ADR-143): the same report as
 * the screen (net quantities held, ADR-131), the current company identity.
 */
@Service
public class RecallPdfs {

    private final LotActions lots;
    private final RecalledLotLabels labels;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;
    private final Clock clock;

    public RecallPdfs(LotActions lots, RecalledLotLabels labels, CompanyIdentity identity, PdfRenderer renderer,
            BusinessSettings business, Clock clock) {
        this.lots = lots;
        this.labels = labels;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('lot.recall')")
    @Transactional(readOnly = true)
    public Pdf render(long lotId) {
        RecallReport report = lots.recallReport(lotId);
        LotLabel label = labels.of(lotId);
        ZoneId zone = business.timeZone();
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.current().orElse(null)));
        model.put("printedAt", PrintFormats.dateTime(clock.instant(), zone));
        Map<String, Object> lot = new HashMap<>();
        lot.put("product", label.productName() + " (" + label.sku() + ")");
        lot.put("lotNumber", label.lotNumber());
        lot.put("expiry", PrintFormats.date(label.expiryDate()));
        lot.put("reason", label.reason());
        lot.put("recalledAt", PrintFormats.dateTime(label.recalledAt(), zone));
        model.put("lot", lot);
        String unit = report.buyers().isEmpty() ? "" : " " + report.buyers().getFirst().unit();
        model.put("remaining", PrintFormats.quantity(report.remainingStockBase()) + unit);
        model.put("buyers", report.buyers().stream().map(b -> Map.of("customer", b.customerName(), "phone",
                b.phone() == null ? "—" : b.phone(), "document", b.documentNumber(), "date",
                PrintFormats.dateTime(b.issuedAt(), zone), "sold", PrintFormats.quantity(b.quantityBase()) + " "
                        + b.unit(), "returned", PrintFormats.quantity(b.returnedBase()), "held",
                PrintFormats.quantity(b.netBase()))).toList());
        model.put("anonymous", report.anonymousSales().stream().map(a -> Map.of("document", a.documentNumber(),
                "date", PrintFormats.dateTime(a.issuedAt(), zone), "sold", PrintFormats.quantity(a.quantityBase()),
                "returned", PrintFormats.quantity(a.returnedBase()), "held", PrintFormats.quantity(a.netBase())))
                .toList());
        return new Pdf(renderer.render("recall-a4", model, Paper.A4), "rappel-" + label.lotNumber() + ".pdf");
    }
}
