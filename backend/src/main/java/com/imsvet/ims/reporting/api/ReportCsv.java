package com.imsvet.ims.reporting.api;

import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.reporting.application.ReportTable;
import com.imsvet.ims.reporting.application.ReportTable.Column;
import com.imsvet.ims.reporting.application.ReportTable.Section;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The CSV export of a report (ADR-149, api.md §4.13 {@code format=csv}): each section's headers, rows and totals, a
 * blank line and the section's heading between sections; downloaded as an attachment, never cached.
 */
@Component
class ReportCsv {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final BusinessSettings business;

    ReportCsv(BusinessSettings business) {
        this.business = business;
    }

    ResponseEntity<byte[]> render(ReportTable table) {
        Csv csv = new Csv(business.timeZone());
        boolean first = true;
        for (Section section : table.sections()) {
            if (!first) {
                csv.row();
            }
            if (table.sections().size() > 1 && section.heading() != null) {
                csv.row(section.heading());
            }
            csv.row(section.columns().stream().map(Column::label).toArray());
            section.rows().forEach(r -> csv.row(r.toArray()));
            section.totals().forEach(r -> csv.row(r.toArray()));
            first = false;
        }
        return ResponseEntity.ok().contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(table.fileName() + ".csv").build().toString())
                .cacheControl(CacheControl.noStore()).body(csv.bytes());
    }
}
