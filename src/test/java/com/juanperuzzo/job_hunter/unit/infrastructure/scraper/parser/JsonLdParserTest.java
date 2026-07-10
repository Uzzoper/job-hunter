package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.parser;

import com.juanperuzzo.job_hunter.infrastructure.scraper.parser.JsonLdParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonLdParser tests")
class JsonLdParserTest {

    private JsonLdParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonLdParser();
    }

    @Test
    @DisplayName("parse should extract JobPosting fields from valid JSON-LD")
    void parse_withValidJobPosting_shouldExtractFields() {
        var html = """
                <html><body>
                <script type="application/ld+json">
                {
                  "@type": "JobPosting",
                  "title": "Desenvolvedor Java Júnior",
                  "description": "We need a Java developer",
                  "hiringOrganization": { "name": "TechCompany" },
                  "datePosted": "2026-07-01",
                  "jobLocation": { "address": { "addressLocality": "São Paulo" } },
                  "employmentType": "CLT",
                  "jobLocationType": "TELECOMMUTE"
                }
                </script>
                </body></html>
                """;

        var result = parser.parse(html);

        assertTrue(result.isPresent());
        var posting = result.get();
        assertEquals("Desenvolvedor Java Júnior", posting.title());
        assertEquals("We need a Java developer", posting.description());
        assertEquals("TechCompany", posting.company());
        assertEquals("2026-07-01", posting.datePosted());
        assertEquals("São Paulo", posting.location());
        assertEquals("CLT", posting.employmentType());
        assertEquals("Remoto", posting.workModel());
    }

    @Test
    @DisplayName("parse should return empty when no script tag exists")
    void parse_withNoScriptTag_shouldReturnEmpty() {
        var html = "<html><body><p>No JSON-LD here</p></body></html>";
        assertTrue(parser.parse(html).isEmpty());
    }

    @Test
    @DisplayName("parse should return empty when malformed JSON in script")
    void parse_withMalformedJson_shouldReturnEmpty() {
        var html = """
                <html><body>
                <script type="application/ld+json">
                { invalid json here }
                </script>
                </body></html>
                """;
        assertTrue(parser.parse(html).isEmpty());
    }

    @Test
    @DisplayName("parse should return empty when JSON-LD is not a JobPosting")
    void parse_withNonJobPosting_shouldReturnEmpty() {
        var html = """
                <html><body>
                <script type="application/ld+json">
                { "@type": "WebPage", "name": "Test" }
                </script>
                </body></html>
                """;
        assertTrue(parser.parse(html).isEmpty());
    }

    @Test
    @DisplayName("parse should handle null HTML gracefully")
    void parse_withNull_shouldReturnEmpty() {
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    @DisplayName("parse should handle empty HTML gracefully")
    void parse_withEmpty_shouldReturnEmpty() {
        assertTrue(parser.parse("").isEmpty());
    }
}
