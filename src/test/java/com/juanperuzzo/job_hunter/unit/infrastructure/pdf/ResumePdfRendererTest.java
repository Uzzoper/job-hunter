package com.juanperuzzo.job_hunter.unit.infrastructure.pdf;

import com.juanperuzzo.job_hunter.infrastructure.pdf.ResumePdfRenderer;
import org.apache.pdfbox.Loader;
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

    private static String extractText(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
