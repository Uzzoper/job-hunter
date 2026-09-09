package com.juanperuzzo.job_hunter.unit.domain;

import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserPreferences validation tests")
class UserPreferencesTest {

    @Nested
    @DisplayName("salaryFloor validation")
    class SalaryFloorTests {

        @Test
        @DisplayName("salaryFloor should accept positive values")
        void salaryFloor_whenPositive_shouldAccept() {
            var prefs = new UserPreferences(null, 5000, null, null);
            assertEquals(5000, prefs.salaryFloor());
        }

        @Test
        @DisplayName("salaryFloor should accept null (unset)")
        void salaryFloor_whenNull_shouldAccept() {
            var prefs = new UserPreferences(null, null, null, null);
            assertNull(prefs.salaryFloor());
        }

        @Test
        @DisplayName("salaryFloor when zero should throw IllegalArgumentException")
        void salaryFloor_whenZero_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, 0, null, null));
        }

        @Test
        @DisplayName("salaryFloor when negative should throw IllegalArgumentException")
        void salaryFloor_whenNegative_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, -100, null, null));
        }

        @Test
        @DisplayName("salaryFloor when above 500,000 should throw IllegalArgumentException")
        void salaryFloor_whenAboveSanityCap_shouldThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, 500_001, null, null));
        }

        @Test
        @DisplayName("salaryFloor at exactly 500,000 should be accepted")
        void salaryFloor_atSanityCap_shouldAccept() {
            var prefs = new UserPreferences(null, 500_000, null, null);
            assertEquals(500_000, prefs.salaryFloor());
        }
    }

    @Nested
    @DisplayName("locations normalization")
    class LocationsTests {

        @Test
        @DisplayName("locations when null should normalize to empty list")
        void locations_whenNull_shouldNormalizeToEmpty() {
            var prefs = new UserPreferences(null, null, null, null);
            assertEquals(List.of(), prefs.locations());
        }

        @Test
        @DisplayName("locations when empty should stay empty")
        void locations_whenEmpty_shouldStayEmpty() {
            var prefs = new UserPreferences(null, null, List.of(), null);
            assertEquals(List.of(), prefs.locations());
        }

        @Test
        @DisplayName("locations should trim items")
        void locations_shouldTrimItems() {
            var prefs = new UserPreferences(null, null, List.of("  São Paulo  ", " Remote "), null);
            assertEquals(List.of("São Paulo", "Remote"), prefs.locations());
        }

        @Test
        @DisplayName("locations should drop blank items after trim")
        void locations_shouldDropBlankItems() {
            var prefs = new UserPreferences(null, null, List.of("São Paulo", "  ", ""), null);
            assertEquals(List.of("São Paulo"), prefs.locations());
        }

        @Test
        @DisplayName("locations when exceeding 20 items should trim to 20")
        void locations_whenExceeding20_shouldTrim() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 25; i++) many.add("City" + i);
            var prefs = new UserPreferences(null, null, many, null);
            assertEquals(20, prefs.locations().size());
        }

        @Test
        @DisplayName("locations item exceeding 100 chars should throw IllegalArgumentException")
        void locationItem_whenExceeding100Chars_shouldThrow() {
            String longName = "A".repeat(101);
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, null, List.of(longName), null));
        }

        @Test
        @DisplayName("locations item beyond 20-item cap that exceeds 100 chars should still throw (deterministic validation-before-limit)")
        void locationItem_beyondCapAndExceedingLength_shouldStillThrow() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 20; i++) many.add("City" + i);
            many.add("A".repeat(101)); // 21st item exceeds 100 chars
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, null, many, null));
        }

        @Test
        @DisplayName("locations item at exactly 100 chars should be accepted")
        void locationItem_atExactly100Chars_shouldAccept() {
            String exactName = "A".repeat(100);
            var prefs = new UserPreferences(null, null, List.of(exactName), null);
            assertEquals(1, prefs.locations().size());
        }

        @Test
        @DisplayName("locations should accept null items within the list")
        void locations_shouldAcceptNullItems() {
            var prefs = new UserPreferences(null, null, Arrays.asList("São Paulo", null, "Remote"), null);
            assertEquals(List.of("São Paulo", "Remote"), prefs.locations());
        }
    }

    @Nested
    @DisplayName("excludedCompanies normalization")
    class ExcludedCompaniesTests {

        @Test
        @DisplayName("excludedCompanies when null should normalize to empty list")
        void excludedCompanies_whenNull_shouldNormalizeToEmpty() {
            var prefs = new UserPreferences(null, null, null, null);
            assertEquals(List.of(), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies when empty should stay empty")
        void excludedCompanies_whenEmpty_shouldStayEmpty() {
            var prefs = new UserPreferences(null, null, null, List.of());
            assertEquals(List.of(), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies should trim items")
        void excludedCompanies_shouldTrimItems() {
            var prefs = new UserPreferences(null, null, null, List.of("  Acme Corp  "));
            assertEquals(List.of("Acme Corp"), prefs.excludedCompanies());
        }

        @Test
        @DisplayName("excludedCompanies when exceeding 50 items should trim to 50")
        void excludedCompanies_whenExceeding50_shouldTrim() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 60; i++) many.add("Company" + i);
            var prefs = new UserPreferences(null, null, null, many);
            assertEquals(50, prefs.excludedCompanies().size());
        }

        @Test
        @DisplayName("company name exceeding 200 chars should throw IllegalArgumentException")
        void companyName_whenExceeding200Chars_shouldThrow() {
            String longName = "B".repeat(201);
            assertThrows(IllegalArgumentException.class,
                    () -> new UserPreferences(null, null, null, List.of(longName)));
        }

        @Test
        @DisplayName("company name at exactly 200 chars should be accepted")
        void companyName_atExactly200Chars_shouldAccept() {
            String exactName = "B".repeat(200);
            var prefs = new UserPreferences(null, null, null, List.of(exactName));
            assertEquals(1, prefs.excludedCompanies().size());
        }
    }

    @Nested
    @DisplayName("WorkModel exact-match tokens")
    class WorkModelTokenTests {

        @Test
        @DisplayName("WorkModel.REMOTE should be a valid enum value")
        void workModel_remote_shouldExist() {
            assertEquals(WorkModel.REMOTE, WorkModel.valueOf("REMOTE"));
        }

        @Test
        @DisplayName("WorkModel.HYBRID should be a valid enum value")
        void workModel_hybrid_shouldExist() {
            assertEquals(WorkModel.HYBRID, WorkModel.valueOf("HYBRID"));
        }

        @Test
        @DisplayName("WorkModel.ONSITE should be a valid enum value")
        void workModel_onsite_shouldExist() {
            assertEquals(WorkModel.ONSITE, WorkModel.valueOf("ONSITE"));
        }

        @Test
        @DisplayName("WorkModel should have exactly 3 values")
        void workModel_shouldHaveExactly3Values() {
            assertEquals(3, WorkModel.values().length);
        }
    }

    @Nested
    @DisplayName("empty() factory")
    class EmptyTests {

        @Test
        @DisplayName("empty should return preferences with all null/empty fields")
        void empty_shouldReturnAllNullOrEmpty() {
            var prefs = UserPreferences.empty();
            assertNull(prefs.workModel());
            assertNull(prefs.salaryFloor());
            assertEquals(List.of(), prefs.locations());
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
                    WorkModel.REMOTE, 8000,
                    List.of("São Paulo", "Remote"),
                    List.of("Acme Corp", "Evil Inc"));
            assertEquals(WorkModel.REMOTE, prefs.workModel());
            assertEquals(8000, prefs.salaryFloor());
            assertEquals(2, prefs.locations().size());
            assertEquals(2, prefs.excludedCompanies().size());
        }

        @Test
        @DisplayName("should accept all null fields (fully unset)")
        void allFieldsNull_shouldAccept() {
            var prefs = new UserPreferences(null, null, null, null);
            assertNull(prefs.workModel());
            assertNull(prefs.salaryFloor());
            assertEquals(List.of(), prefs.locations());
            assertEquals(List.of(), prefs.excludedCompanies());
        }
    }
}
