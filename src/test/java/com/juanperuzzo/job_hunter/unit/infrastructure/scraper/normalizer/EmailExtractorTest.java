package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.EmailExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Nested
    @DisplayName("guarantees: exclusions, placeholders, literal-only")
    class Guarantees {

        @Test
        @DisplayName("should never return excluded local parts when a real address is present")
        void extract_whenExcludedShapesAlongsideRealAddress_shouldReturnRealAddress() {
            record Case(String description, String expected) {
            }
            var cases = List.of(
                    new Case("noreply@empresa.com.br ou rh@empresa.com.br", "rh@empresa.com.br"),
                    new Case("donotreply@empresa.com.br e rh@empresa.com.br", "rh@empresa.com.br"),
                    new Case("no-reply@empresa.com.br / rh@empresa.com.br", "rh@empresa.com.br"),
                    new Case("apply@empresa.com.br ou rh@empresa.com.br", "rh@empresa.com.br"));
            for (var c : cases) {
                assertEquals(c.expected(), EmailExtractor.extract("", c.description()),
                        "description: " + c.description());
            }
        }

        @Test
        @DisplayName("should return null when only excluded or placeholder addresses are present")
        void extract_whenOnlyExcludedOrPlaceholder_shouldReturnNull() {
            for (var text : List.of(
                    "noreply@empresa.com.br",
                    "donotreply@empresa.com.br",
                    "no-reply@empresa.com.br",
                    "apply@empresa.com.br",
                    "contato@example.com",
                    "contato@exemplo.com",
                    "contato@test.com",
                    "contato@domain.com",
                    "contato@yourdomain.com",
                    "contato@seuemail.com")) {
                assertNull(EmailExtractor.extract("", text), "text: " + text);
            }
        }

        @Test
        @DisplayName("should never invent an address from shuffled obfuscation fragments")
        void extract_whenShuffledObfuscationFragments_shouldReturnNull() {
            for (var text : List.of(
                    "com ponto br arroba rh empresa",
                    "empresa ponto rh com arroba br",
                    "br com rh empresa arroba ponto",
                    "vaga arroba",
                    "arroba empresa.com.br",
                    "rh ponto com",
                    "at rh empresa com br",
                    "@",
                    "email @ vaga")) {
                assertNull(EmailExtractor.extract("", text), "text: " + text);
            }
        }

        /**
         * Shuffled fragments must never self-assemble into an address. Two token sets
         * are checked separately because mixing {@code arroba} and {@code ponto} can
         * legitimately reconstruct a real address when the fragments stay contiguous
         * in the source order (e.g. "empresa arroba br ponto com" → "empresa@br.com"
         * is the spec's intended word-obfuscation decoding, not an invention). With
         * only one @-source (or only one dot-source) present, an <em>@</em> can never
         * pair with a dotted domain, so every permutation must yield null.
         */
        @Test
        @DisplayName("should never invent an address from shuffled fragments without a dotted counterpart")
        void extract_whenShuffledFragmentsCannotSelfAssemble_shouldReturnNull() {
            for (var permutation : permutations(List.of("rh", "empresa", "com", "br", "arroba"), 0)) {
                var text = String.join(" ", permutation);
                assertNull(EmailExtractor.extract("", text), "permutation: " + text);
            }
            for (var permutation : permutations(List.of("rh", "empresa", "com", "br", "ponto"), 0)) {
                var text = String.join(" ", permutation);
                assertNull(EmailExtractor.extract("", text), "permutation: " + text);
            }
        }

        @Test
        @DisplayName("should reconstruct exactly the canonical address from its obfuscation variants")
        void extract_whenObfuscationVariantsOfCanonicalAddress_shouldReconstructExactly() {
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh[at]empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh(at)empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh at empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh[arroba]empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh(arroba)empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh arroba empresa ponto com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh\u200C[at]empresa.com"));
            assertEquals("rh@empresa.com", EmailExtractor.extract("", "rh&#64;empresa.com"));
        }

        @Test
        @DisplayName("should only ever return a literal substring of the source text")
        void extract_whenPlainTextSource_shouldReturnContainedLiteral() {
            for (var pair : List.of(
                    new String[]{"Contato rh@empresa.com.br para dúvidas", "rh@empresa.com.br"},
                    new String[]{"e-mail: vagas@empresa.com.br", "vagas@empresa.com.br"},
                    new String[]{"veja joao.silva@empresa.com.br no rodapé", "joao.silva@empresa.com.br"})) {
                var actual = EmailExtractor.extract("", pair[0]);
                assertNotNull(actual, "text: " + pair[0]);
                assertTrue(pair[0].contains(actual), "text: " + pair[0] + " does not contain " + actual);
            }
        }
    }

    /** All permutations of {@code items} starting at {@code index} (simple copy-on-recurse). */
    private static List<List<String>> permutations(List<String> items, int index) {
        var mutable = new java.util.ArrayList<>(items);
        var results = new java.util.ArrayList<List<String>>();
        if (index == mutable.size() - 1) {
            results.add(mutable);
            return results;
        }
        for (int i = index; i < mutable.size(); i++) {
            swap(mutable, index, i);
            results.addAll(permutations(mutable, index + 1));
            swap(mutable, index, i);
        }
        return results;
    }

    private static void swap(List<String> items, int i, int j) {
        var tmp = items.get(i);
        items.set(i, items.get(j));
        items.set(j, tmp);
    }
}