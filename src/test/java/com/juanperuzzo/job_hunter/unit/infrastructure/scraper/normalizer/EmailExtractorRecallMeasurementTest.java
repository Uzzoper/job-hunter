package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.EmailExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recall/precision baseline for {@link EmailExtractor} per the
 * {@code docs/specs/email-extractor-recall.md} audit plan.
 * <p>
 * This test owns the fixture corpus ({@code src/test/resources/email-fixtures/fixtures.json})
 * and PRINTS the metrics — it deliberately does NOT assert on recall/precision values:
 * the numbers are the measured baseline for the report, and Phase 2 gap fixes are meant
 * to change them. Only structural invariants of the corpus itself are asserted.
 */
class EmailExtractorRecallMeasurementTest {

    private static final String FIXTURES_PATH = "/email-fixtures/fixtures.json";

    /** Spec-mandated categories that must be present in the corpus. */
    private static final List<String> REQUIRED_CATEGORIES = List.of(
            "plain", "mailto", "bracket-at", "paren-at", "bracket-dot", "paren-dot",
            "bracket-arroba", "paren-arroba", "bare-arroba", "bare-at", "zero-width",
            "multi-address", "no-email", "excluded", "placeholder", "decoy");

    record Fixture(
            String id, String category, String title, String description,
            String expected, boolean real, String source) {
    }

    @Test
    @DisplayName("fixture corpus meets structural invariants")
    void fixtureCorpus_meetsStructuralInvariants_passes() throws IOException {
        var fixtures = loadFixtures();

        assertTrue(fixtures.size() >= 30, "expected at least 30 fixtures, got " + fixtures.size());

        var ids = new HashSet<String>();
        var categories = new HashSet<String>();
        for (var fixture : fixtures) {
            assertTrue(ids.add(fixture.id()), "duplicate fixture id: " + fixture.id());
            assertTrue(fixture.title() != null && !fixture.title().isBlank(),
                    fixture.id() + " must have a non-blank title");
            assertTrue(fixture.description() != null && !fixture.description().isBlank(),
                    fixture.id() + " must have a non-blank description");
            categories.add(fixture.category());

            if (fixture.expected() == null) {
                continue;
            }
            assertTrue(fixture.expected().contains("@"),
                    fixture.id() + " expected value must contain an @");
            var domain = fixture.expected().substring(fixture.expected().indexOf('@') + 1);
            assertTrue(domain.contains("."),
                    fixture.id() + " expected value must have a dotted domain");
        }

        for (var category : REQUIRED_CATEGORIES) {
            assertTrue(categories.contains(category), "missing mandated category: " + category);
        }
    }

    @Test
    @DisplayName("recall/precision baseline measurement (prints metrics, asserts nothing on values)")
    void measurement_reportsBaselineMetrics_printsMetrics() throws IOException {
        var fixtures = loadFixtures();

        int withEmail = 0;
        int correct = 0;
        int anyPick = 0;
        int falsePositive = 0;

        var rows = new StringBuilder();
        for (var fixture : fixtures) {
            var actual = EmailExtractor.extract(fixture.title(), fixture.description());
            var expected = fixture.expected();

            if (expected != null) {
                withEmail++;
                if (expected.equals(actual)) {
                    correct++;
                } else {
                    rows.append(String.format(Locale.ROOT,
                            "MISS  %s [%s] expected=%s actual=%s%n",
                            fixture.id(), fixture.category(), expected, actual));
                }
            }
            if (actual != null) {
                anyPick++;
                if (expected == null) {
                    falsePositive++;
                    rows.append(String.format(Locale.ROOT,
                            "FP    %s [%s] expected=null actual=%s%n",
                            fixture.id(), fixture.category(), actual));
                }
            }
        }

        double recall = withEmail == 0 ? 0.0 : (double) correct / withEmail;
        double precision = anyPick == 0 ? 0.0 : (double) correct / anyPick;

        System.out.println("=== EmailExtractor recall measurement ===");
        System.out.printf(Locale.ROOT, "fixtures total      : %d%n", fixtures.size());
        System.out.printf(Locale.ROOT, "with expected email : %d%n", withEmail);
        System.out.printf(Locale.ROOT, "correct picks       : %d%n", correct);
        System.out.printf(Locale.ROOT, "recall              : %.3f (%d/%d)%n", recall, correct, withEmail);
        System.out.printf(Locale.ROOT, "any picks           : %d%n", anyPick);
        System.out.printf(Locale.ROOT, "precision           : %.3f (%d/%d)%n", precision, correct, anyPick);
        System.out.printf(Locale.ROOT, "false positives     : %d of %d no-email fixtures%n",
                falsePositive, fixtures.size() - withEmail);
        System.out.println("--- per-fixture misses ---");
        System.out.print(rows);
    }

    private static List<Fixture> loadFixtures() throws IOException {
        try (InputStream is = EmailExtractorRecallMeasurementTest.class.getResourceAsStream(FIXTURES_PATH)) {
            assertNotNull(is, "missing test resource " + FIXTURES_PATH);
            return new ObjectMapper().readValue(is, new TypeReference<List<Fixture>>() {
            });
        }
    }
}