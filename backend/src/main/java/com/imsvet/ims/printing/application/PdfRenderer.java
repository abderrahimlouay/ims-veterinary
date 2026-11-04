package com.imsvet.ims.printing.application;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.TextDirection;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * HTML templates to PDF (ADR-138): Thymeleaf fills {@code printing/templates/*.html}, openhtmltopdf lays it out with
 * the bundled Noto fonts (Latin, and Arabic shaped and reordered by ICU), nothing from the network. A ticket is 80 mm
 * wide and exactly as tall as its content (ADR-139): laid out once on a long strip, then rendered at that height.
 */
@Component
public class PdfRenderer {

    /** The page: A4 portrait, or the 80 mm strip of a thermal ticket. */
    public enum Paper {
        A4, TICKET
    }

    private static final String FONTS = "/printing/fonts/";
    private static final int TICKET_PROBE_MM = 2000;
    private static final int TICKET_MARGIN_MM = 6;

    private final TemplateEngine templates;

    public PdfRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("printing/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        templates = new TemplateEngine();
        templates.setTemplateResolver(resolver);
    }

    /** The template filled with {@code model} (plus {@code pageHeightMm} for a ticket), as PDF bytes. */
    public byte[] render(String template, Map<String, Object> model, Paper paper) {
        if (paper == Paper.A4) {
            return pdf(html(template, model, null));
        }
        int height;
        try (PdfBoxRenderer probe = builder(html(template, model, TICKET_PROBE_MM)).buildPdfRenderer()) {
            probe.layout();
            float dots = probe.getRootBox().getHeight();
            float dotsPerPixel = probe.getSharedContext().getDotsPerPixel();
            height = (int) Math.ceil(dots / dotsPerPixel / 96f * 25.4f) + TICKET_MARGIN_MM;
        }
        return pdf(html(template, model, height));
    }

    private String html(String template, Map<String, Object> model, Integer pageHeightMm) {
        Map<String, Object> variables = new HashMap<>(model);
        if (pageHeightMm != null) {
            variables.put("pageHeightMm", pageHeightMm);
        }
        return templates.process(template, new Context(Locale.FRENCH, variables));
    }

    private static byte[] pdf(String html) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            builder(html).toStream(out).run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static PdfRendererBuilder builder(String html) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.withHtmlContent(html, null);
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
        builder.defaultTextDirection(TextDirection.LTR);
        font(builder, "NotoSans-Regular.ttf", "Noto Sans", 400);
        font(builder, "NotoSans-Bold.ttf", "Noto Sans", 700);
        font(builder, "NotoNaskhArabic-Regular.ttf", "Noto Naskh Arabic", 400);
        font(builder, "NotoNaskhArabic-Bold.ttf", "Noto Naskh Arabic", 700);
        return builder;
    }

    private static void font(PdfRendererBuilder builder, String file, String family, int weight) {
        builder.useFont(() -> stream(file), family, weight, FontStyle.NORMAL, true);
    }

    private static InputStream stream(String file) {
        InputStream in = PdfRenderer.class.getResourceAsStream(FONTS + file);
        if (in == null) {
            throw new IllegalStateException("Missing bundled font " + file);
        }
        return in;
    }
}
