package com.juanperuzzo.job_hunter.unit.infrastructure.pdf;

import com.juanperuzzo.job_hunter.domain.exception.ResumeRenderingException;
import com.juanperuzzo.job_hunter.infrastructure.pdf.ResumePdfRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResumePdfRenderer tests")
class ResumePdfRendererTest {

    private final ResumePdfRenderer renderer = new ResumePdfRenderer();

    @Test
    @DisplayName("render should produce a real PDF containing the injected text")
    void render_whenValidHtml_shouldProducePdfWithText() throws Exception {
        var html = """
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                <h1>JUAN ANTONIO PERUZZO</h1>
                <p>Desenvolvedor Java Júnior com foco em Spring Boot</p>
                </body>
                </html>
                """;

        byte[] pdf = renderer.renderPdf(html);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "PDF must not be empty");
        assertTrue(pdf[0] == 0x25 && pdf[1] == 0x50 && pdf[2] == 0x44 && pdf[3] == 0x46,
                "output must start with the %PDF header");

        String text = extractText(pdf);
        assertTrue(text.contains("JUAN ANTONIO PERUZZO"), "PDF text must contain the name");
        assertTrue(text.contains("Desenvolvedor Java Júnior"), "PDF text must contain the tailored objective");
    }

    @Test
    @DisplayName("render should support Portuguese accents (bundled font)")
    void render_whenHtmlHasAccents_shouldRenderAccents() throws Exception {
        var html = """
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                <p>Currículo — Desenvolvedor Júnior com experiência em PostgreSQL</p>
                <ul>
                <li>Análise de requisitos</li>
                <li>Desenvolvimento de APIs REST</li>
                </ul>
                </body>
                </html>
                """;

        byte[] pdf = renderer.renderPdf(html);
        String text = extractText(pdf);

        assertTrue(text.contains("Currículo"), "accented word Currículo must be extractable");
        assertTrue(text.contains("experiência"), "accented word experiência must be extractable");
        assertTrue(text.contains("Análise"), "accented word Análise must be extractable");
        assertTrue(text.contains("APIs"), "plain text must be extractable");
    }

    @Test
    @DisplayName("render should produce a clickable link annotation for anchor elements")
    void render_whenHtmlHasAnchor_shouldProduceClickableLink() throws Exception {
        var html = """
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                <p><a href="https://github.com/Uzzoper/job-hunter">https://github.com/Uzzoper/job-hunter</a></p>
                </body>
                </html>
                """;

        byte[] pdf = renderer.renderPdf(html);

        try (var doc = Loader.loadPDF(pdf)) {
            var annotations = doc.getPage(0).getAnnotations();
            var link = annotations.stream()
                    .filter(a -> a instanceof PDAnnotationLink)
                    .map(a -> (PDAnnotationLink) a)
                    .findFirst()
                    .orElse(null);
            assertNotNull(link, "PDF must contain a link annotation");
            assertTrue(link.getAction() instanceof PDActionURI,
                    "link annotation must have a URI action");
            assertEquals("https://github.com/Uzzoper/job-hunter",
                    ((PDActionURI) link.getAction()).getURI());
        }
    }

    @Test
    @DisplayName("render should throw ResumeRenderingException when the HTML is malformed")
    void render_whenHtmlIsMalformed_shouldThrowResumeRenderingException() {
        // A non-void element left unclosed cannot be auto-fixed by the
        // void-element self-closing pass, so strict XML parsing fails.
        var malformedHtml = "<html><body><div>unclosed element";

        var ex = assertThrows(ResumeRenderingException.class,
                () -> renderer.renderPdf(malformedHtml));

        assertTrue(ex.getMessage().contains("Failed to render PDF"),
                "message must describe the rendering failure");
        assertNotNull(ex.getCause(), "the underlying parse error must be preserved as cause");
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
