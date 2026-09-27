package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.EmailExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Targeted tests for the {@code docs/specs/email-extractor-recall.md} Phase 2 gaps,
 * complementing the fixture-driven measurement suite.
 */
class EmailExtractorTest {

    @Nested
    @DisplayName("bare word obfuscations (arroba/ponto)")
    class BareWordForms {

        @Test
        @DisplayName("should reconstruct an address from space-padded 'arroba' and 'ponto'")
        void extract_whenBareArrobaAndPontoSpaced_shouldReconstructAddress() {
            assertEquals("vaga@empresa.com",
                    EmailExtractor.extract("", "Entre em contato: vaga arroba empresa ponto com"));
        }

        @Test
        @DisplayName("should reconstruct an address from bare 'at' followed by bare 'ponto'")
        void extract_whenBareAtAndBarePontoSpaced_shouldReconstructAddress() {
            assertEquals("rh@empresa.com",
                    EmailExtractor.extract("", "Mensagens para rh at empresa ponto com"));
        }

        @Test
        @DisplayName("should return null when bare 'arroba' has no dotted domain")
        void extract_whenBareArrobaOnly_shouldReturnNull() {
            assertNull(EmailExtractor.extract("", "Envie para vaga arroba empresa"));
        }

        @Test
        @DisplayName("should never fire on plain Portuguese prose containing 'ponto de encontro'")
        void extract_whenPontoDeEncontroProse_shouldReturnNull() {
            assertNull(EmailExtractor.extract("", "Nos vemos no ponto de encontro às 18h para o café."));
        }

        @Test
        @DisplayName("should never invent an address from bare 'ponto' without a literal @")
        void extract_whenBarePontoWithoutAt_shouldReturnNull() {
            assertNull(EmailExtractor.extract("", "vaga ponto com"));
        }

        @Test
        @DisplayName("should not decode hyphen-separated obfuscations (conservative spacing)")
        void extract_whenHyphenSeparatedObfuscations_shouldReturnNull() {
            assertNull(EmailExtractor.extract("", "vaga-arroba-empresa-ponto-com"));
        }

        @Test
        @DisplayName("should keep decoding the bracketed/parenthesised forms already covered")
        void extract_whenExistingObscuredForms_shouldKeepReconstructingAddresses() {
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh[at]empresa.com.br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh(at)empresa.com.br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh[arroba]empresa.com.br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh(arroba)empresa.com.br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh@empresa[dot]com[dot]br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh@empresa(dot)com(dot)br"));
            assertEquals("rh@empresa.com.br", EmailExtractor.extract("", "rh at empresa.com.br"));
        }
    }
}