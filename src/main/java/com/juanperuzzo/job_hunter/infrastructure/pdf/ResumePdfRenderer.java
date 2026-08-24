package com.juanperuzzo.job_hunter.infrastructure.pdf;

import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.domain.exception.ResumeRenderingException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * Renders HTML documents into PDF bytes using OpenHTMLToPDF (PDFBox 3 fork).
 * Bundles the DejaVuSans font for full PT-BR accent support.
 */
public class ResumePdfRenderer implements PdfRendererPort {

    private static final Logger log = LoggerFactory.getLogger(ResumePdfRenderer.class);
    private static final String FONT_PATH = "fonts/DejaVuSans.ttf";
    private static final String FONT_FAMILY = "DejaVuSans";

    /**
     * OpenHTMLToPDF parses the document as strict XML, so void HTML elements
     * (meta, br, img, ...) must be self-closed. This pattern matches a void
     * element tag that does not already end with "/>".
     */
    private static final Pattern VOID_ELEMENT_PATTERN = Pattern.compile(
            "(<(?:meta|link|br|hr|img|input|source|area|base|col|embed|param|track|wbr)\\b[^>]*?)(?<!/)>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Renders the given HTML document into PDF bytes.
     *
     * @param html the HTML document to render
     * @return the rendered PDF bytes
     * @throws ResumeRenderingException if the PDF rendering fails or the bundled
     *                                  font is missing from the classpath
     */
    @Override
    public byte[] renderPdf(String html) {
        try {
            byte[] fontBytes = loadFont();
            var baos = new ByteArrayOutputStream();
            var builder = new PdfRendererBuilder();
            builder.useFont(() -> new ByteArrayInputStream(fontBytes), FONT_FAMILY);
            builder.withHtmlContent(toWellFormedXml(html), null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render PDF", e);
            throw new ResumeRenderingException("Failed to render PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Self-closes void HTML elements so the document is well-formed XML,
     * which OpenHTMLToPDF requires.
     */
    private String toWellFormedXml(String html) {
        return VOID_ELEMENT_PATTERN.matcher(html).replaceAll("$1 />");
    }

    private byte[] loadFont() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FONT_PATH)) {
            if (in == null) {
                throw new ResumeRenderingException("Font not found in classpath: " + FONT_PATH);
            }
            return in.readAllBytes();
        }
    }
}