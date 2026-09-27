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

    @Nested
    @DisplayName("multi-address ranking by local part")
    class MultiAddressRanking {

        @Test
        @DisplayName("should pick the hiring address when it appears after a generic one")
        void extract_whenHiringAddressAfterGenericInSameText_shouldPickHiring() {
            assertEquals("vagas@empresa.com.br",
                    EmailExtractor.extract("", "Para dúvidas use contato@empresa.com.br ou envie seu CV para vagas@empresa.com.br."));
        }

        @Test
        @DisplayName("should pick the hiring address when it appears after a personal one")
        void extract_whenHiringAddressAfterPersonal_shouldPickHiring() {
            assertEquals("rh@empresa.com.br",
                    EmailExtractor.extract("", "Fale com João: joao.silva@empresa.com.br ou envie para rh@empresa.com.br"));
        }

        @Test
        @DisplayName("should keep the first address when all candidates are generic")
        void extract_whenAllGenericAddresses_shouldPickFirst() {
            assertEquals("contato@empresa.com.br",
                    EmailExtractor.extract("", "contato@empresa.com.br e sac@empresa.com.br"));
        }

        @Test
        @DisplayName("should keep the first address when several candidates are hiring-specific")
        void extract_whenMultipleHiringAddresses_shouldPickFirstInOrder() {
            assertEquals("vagas@empresa.com.br",
                    EmailExtractor.extract("", "vagas@empresa.com.br ou rh@empresa.com.br"));
        }

        @Test
        @DisplayName("should keep the title winner when the description holds a hiring address")
        void extract_whenGenericInTitleAndHiringInDescription_shouldKeepTitleWinner() {
            assertEquals("info@empresa.com.br",
                    EmailExtractor.extract("Vaga — info@empresa.com.br", "Envie seu currículo para vagas@empresa.com.br."));
        }

        @Test
        @DisplayName("should keep the mailto winner even when a hiring address appears in plain text")
        void extract_whenGenericMailtoAndHiringInPlainText_shouldKeepMailtoWinner() {
            assertEquals("contato@empresa.com.br",
                    EmailExtractor.extract("", "<a href=\"mailto:contato@empresa.com.br\">Apply</a> — ou envie para rh@empresa.com.br."));
        }

        @Test
        @DisplayName("should rank hiring mailto anchors above generic ones in the same text")
        void extract_whenMailtoHasHiringAndGenericAnchors_shouldPickHiring() {
            assertEquals("vagas@empresa.com.br",
                    EmailExtractor.extract("", "<a href=\"mailto:contato@empresa.com.br\">Contato</a> <a href=\"mailto:vagas@empresa.com.br\">Candidatar</a>"));
        }
    }
}