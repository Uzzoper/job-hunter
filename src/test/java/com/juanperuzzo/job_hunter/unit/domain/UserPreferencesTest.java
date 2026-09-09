package com.juanperuzzo.job_hunter.unit.domain;

import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserPreferences validation tests")
class UserPreferencesTest {

    // ── WorkPreference sum type ───────────────────────────────────────
    // Contradictions must be unrepresentable: Remote carries no data,
    // Hybrid/Onsite require ≥ 1 non-blank city.

    @Nested
    @DisplayName("WorkPreference.Remote")
    class RemoteTests {

        @Test
        @DisplayName("Remote should exist with no fields")
        void remote_shouldExist() {
            var remote = new WorkPreference.Remote();
            assertNotNull(remote);
            assertInstanceOf(WorkPreference.class, remote);
        }
    }

    @Nested
    @DisplayName("WorkPreference.Hybrid validation")
    class HybridTests {

        @Test
        @DisplayName("Hybrid should reject null city list (unrepresentable)")
        void hybrid_whenNullCities_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Hybrid(null));
        }

        @Test
        @DisplayName("Hybrid should reject empty city list (unrepresentable)")
        void hybrid_whenEmptyCities_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Hybrid(List.of()));
        }

        @Test
        @DisplayName("Hybrid should reject all-blank city list")
        void hybrid_whenAllBlankCities_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Hybrid(List.of("  ", "")));
        }

        @Test
        @DisplayName("Hybrid should trim cities and drop blanks")
        void hybrid_shouldTrimAndDropBlankCities() {
            var hybrid = new WorkPreference.Hybrid(List.of("  Curitiba  ", "São Paulo", "  "));
            assertEquals(List.of("Curitiba", "São Paulo"), hybrid.cities());
        }

        @Test
        @DisplayName("Hybrid should accept null items within the list")
        void hybrid_shouldAcceptNullItems() {
            var hybrid = new WorkPreference.Hybrid(Arrays.asList("Curitiba", null, "Remote"));
            assertEquals(List.of("Curitiba", "Remote"), hybrid.cities());
        }

        @Test
        @DisplayName("Hybrid when exceeding 20 cities should trim to 20")
        void hybrid_whenExceeding20Cities_shouldTrim() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 25; i++) many.add("City" + i);
            var hybrid = new WorkPreference.Hybrid(many);
            assertEquals(20, hybrid.cities().size());
        }

        @Test
        @DisplayName("Hybrid city exceeding 100 chars should throw IllegalArgumentException")
        void hybrid_whenCityExceeds100Chars_shouldThrow() {
            String longCity = "A".repeat(101);
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Hybrid(List.of(longCity)));
        }

        @Test
        @DisplayName("Hybrid city beyond 20-item cap that exceeds 100 chars should still throw (deterministic validation-before-limit)")
        void hybrid_whenCityBeyondCapExceedsLength_shouldStillThrow() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 20; i++) many.add("City" + i);
            many.add("A".repeat(101)); // 21st item exceeds 100 chars
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Hybrid(many));
        }

        @Test
        @DisplayName("Hybrid city at exactly 100 chars should be accepted")
        void hybrid_whenCityExactly100Chars_shouldAccept() {
            String exactCity = "A".repeat(100);
            var hybrid = new WorkPreference.Hybrid(List.of(exactCity));
            assertEquals(1, hybrid.cities().size());
        }
    }

    @Nested
    @DisplayName("WorkPreference.Onsite validation")
    class OnsiteTests {

        @Test
        @DisplayName("Onsite should reject null city list (unrepresentable)")
        void onsite_whenNullCities_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkPreference.Onsite(null));
        }

        @Test
        @DisplayName("Onsite should accept a valid city list")
        void onsite_whenValidCities_shouldAccept() {
            var onsite = new WorkPreference.Onsite(List.of("São Paulo"));
            assertEquals(List.of("São Paulo"), onsite.cities());
        }

        @Test
        @DisplayName("Onsite should trim cities and drop blanks")
        void onsite_shouldTrimAndDropBlankCities() {
            var onsite = new WorkPreference.Onsite(List.of("  Curitiba  ", "  "));
            assertEquals(List.of("Curitiba"), onsite.cities());
        }
    }

    // ── salaryFloor validation (unchanged) ───────────────────────────

    @Nested
    @DisplayName("salaryFloor validation")
    class SalaryFloorTests {

        @Test
        @DisplayName("salaryFloor should accept positive values")
        void salaryFloor_whenPositive_shouldAccept() {
            var prefs = new UserPreferences(null, 5000, null);
            assertEquals(5000, prefs.salaryFloor());
        }

        @Test
        @DisplayName("salaryFloor should accept null (unset)")
        void salaryFloor_whenNull_shouldAccept() {
            var prefs = new UserPreferences(null, null, null);
            assertNull(prefs.salaryFloor());
        }

        @Test
        @DisplayName("salaryFloor when zero should throw IllegalArgumentException")
        void salaryFloor_whenZero_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, 0, null));
        }

        @Test
        @DisplayName("salaryFloor when negative should throw IllegalArgumentException")
        void salaryFloor_whenNegative_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, -100, null));
        }

        @Test
        @DisplayName("salaryFloor when above 500,000 should throw IllegalArgumentException")
        void salaryFloor_whenAboveSanityCap_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, 500_001, null));
        }

        @Test
        @DisplayName("salaryFloor at exactly 500,000 should be accepted")
        void salaryFloor_atSanityCap_shouldAccept() {
            var prefs = new UserPreferences(null, 500_000, null);
            assertEquals(500_000, prefs.salaryFloor());
        }
    }

    // ── excludedCompanies normalization (unchanged) ──────────────────

    @Nested
    @DisplayName("excludedCompanies normalization")
    class ExcludedCompaniesTests {

        @Test
        @DisplayName("excludedCompanies when null should normalize to empty list")
        void excludedCompanies_whenNull_shouldNormalizeToEmpty() {
            var prefs = new UserPreferences(null, null, null);
            assertEquals(List.of(), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies when empty should stay empty")
        void excludedCompanies_whenEmpty_shouldStayEmpty() {
            var prefs = new UserPreferences(null, null, List.of());
            assertEquals(List.of(), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies should trim items")
        void excludedCompanies_shouldTrimItems() {
            var prefs = new UserPreferences(null, null, List.of("  Acme Corp  "));
            assertEquals(List.of("Acme Corp"), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies when exceeding 50 items should trim to 50")
        void excludedCompanies_whenExceeding50_shouldTrim() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 60; i++) many.add("Company" + i);
            var prefs = new UserPreferences(null, null, many);
            assertEquals(50, prefs.excludedCompanies().size());
        }

        @Test
        @DisplayName("company name exceeding 200 chars should throw IllegalArgumentException")
        void companyName_whenExceeding200Chars_shouldThrow() {
            String longName = "B".repeat(201);
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, null, List.of(longName)));
        }

        @Test
        @DisplayName("company name at exactly 200 chars should be accepted")
        void companyName_atExactly200Chars_shouldAccept() {
            String exactName = "B".repeat(200);
            var prefs = new UserPreferences(null, null, List.of(exactName));
            assertEquals(1, prefs.excludedCompanies().size());
        }
    }

    @Nested
    @DisplayName("empty() factory")
    class EmptyTests {

        @Test
        @DisplayName("empty should return preferences with all null/empty fields")
        void empty_shouldReturnAllNullOrEmpty() {
            var prefs = UserPreferences.empty();
            assertNull(prefs.workPreference());
            assertNull(prefs.salaryFloor());
            assertEquals(List.of(), prefs.excludedCompanies());
        }
    }

    @Nested
    @DisplayName("all fields combined")
    class CombinedTests {

        @Test
        @DisplayName("should accept all fields set simultaneously")
        void allFieldsSet_shouldAccept() {
            var prefs = new UserPreferences(
                    new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo")), 8000,
                    List.of("Acme Corp", "Evil Inc"));
            assertEquals(new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo")), prefs.workPreference());
            assertEquals(8000, prefs.salaryFloor());
            assertEquals(2, prefs.excludedCompanies().size());
        }

        @Test
        @DisplayName("should accept all null fields (fully unset)")
        void allFieldsNull_shouldAccept() {
            var prefs = new UserPreferences(null, null, null);
            assertNull(prefs.workPreference());
            assertNull(prefs.salaryFloor());
            assertEquals(List.of(), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("should accept Remote alongside salary and excluded companies")
        void remoteWithOtherFields_shouldAccept() {
            var prefs = new UserPreferences(
                    new WorkPreference.Remote(), 9000, List.of("Acme Corp"));
            assertInstanceOf(WorkPreference.Remote.class, prefs.workPreference());
            assertEquals(9000, prefs.salaryFloor());
        }
    }
}