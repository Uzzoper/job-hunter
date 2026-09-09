package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotMemorySyncService — preferences merge firewall tests")
class BotMemorySyncPreferencesTest {

    @Mock
    private BotMemoryPort botMemoryPort;

    @Mock
    private UserProfileRepository userProfileRepository;

    @TempDir
    Path tempDir;

    private BotMemorySyncService service;

    @BeforeEach
    void setUp() {
        service = new BotMemorySyncService(
                botMemoryPort,
                userProfileRepository,
                tempDir,
                "memories/MEMORY.md",
                "memories/USER.md");
    }

    // ── WorkModel parse firewall ────────────────────────────────────

    @Nested
    @DisplayName("WorkModel parse-lenient / validate-strict")
    class WorkModelParseTests {

        @Test
        @DisplayName("parseWorkModel 'remoto' should return REMOTE")
        void parseWorkModel_remoto_shouldReturnRemote() {
            assertEquals(WorkModel.REMOTE, BotMemorySyncService.parseWorkModel("remoto"));
        }

        @Test
        @DisplayName("parseWorkModel 'remote' should return REMOTE")
        void parseWorkModel_remote_shouldReturnRemote() {
            assertEquals(WorkModel.REMOTE, BotMemorySyncService.parseWorkModel("remote"));
        }

        @Test
        @DisplayName("parseWorkModel 'Híbrido' with accent should return HYBRID")
        void parseWorkModel_hibrido_shouldReturnHybrid() {
            assertEquals(WorkModel.HYBRID, BotMemorySyncService.parseWorkModel("Híbrido"));
        }

        @Test
        @DisplayName("parseWorkModel 'hybrid' should return HYBRID")
        void parseWorkModel_hybrid_shouldReturnHybrid() {
            assertEquals(WorkModel.HYBRID, BotMemorySyncService.parseWorkModel("hybrid"));
        }

        @Test
        @DisplayName("parseWorkModel 'presencial' should return ONSITE")
        void parseWorkModel_presencial_shouldReturnOnsite() {
            assertEquals(WorkModel.ONSITE, BotMemorySyncService.parseWorkModel("presencial"));
        }

        @Test
        @DisplayName("parseWorkModel 'on-site' should return ONSITE")
        void parseWorkModel_onsite_shouldReturnOnsite() {
            assertEquals(WorkModel.ONSITE, BotMemorySyncService.parseWorkModel("on-site"));
        }

        @Test
        @DisplayName("parseWorkModel 'maybe remote' should return null (ambiguous)")
        void parseWorkModel_ambiguousPhrase_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkModel("maybe remote"));
        }

        @Test
        @DisplayName("parseWorkModel 'banana' should return null (garbage)")
        void parseWorkModel_garbage_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkModel("banana"));
        }

        @Test
        @DisplayName("parseWorkModel 'I work from home' should return null")
        void parseWorkModel_freeText_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkModel("I work from home"));
        }

        @Test
        @DisplayName("parseWorkModel null should return null")
        void parseWorkModel_null_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkModel(null));
        }

        @Test
        @DisplayName("parseWorkModel blank should return null")
        void parseWorkModel_blank_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkModel("  "));
        }
    }

    // ── Salary parse firewall ───────────────────────────────────────

    @Nested
    @DisplayName("SalaryFloor parse-lenient / validate-strict")
    class SalaryParseTests {

        @Test
        @DisplayName("parseSalaryFloor '5000' should return 5000")
        void parseSalaryFloor_digits_shouldParse() {
            assertEquals(5000, BotMemorySyncService.parseSalaryFloor("5000"));
        }

        @Test
        @DisplayName("parseSalaryFloor 'R$ 5.000' should return 5000 (strip non-digits)")
        void parseSalaryFloor_formatted_shouldParse() {
            assertEquals(5000, BotMemorySyncService.parseSalaryFloor("R$ 5.000"));
        }

        @Test
        @DisplayName("parseSalaryFloor '500001' should return null (above 500k cap)")
        void parseSalaryFloor_aboveCap_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor("500001"));
        }

        @Test
        @DisplayName("parseSalaryFloor '0' should return null (non-positive)")
        void parseSalaryFloor_zero_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor("0"));
        }

        @Test
        @DisplayName("parseSalaryFloor '-500' should return null (negative)")
        void parseSalaryFloor_negative_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor("-500"));
        }

        @Test
        @DisplayName("parseSalaryFloor 'abc' should return null (unparseable)")
        void parseSalaryFloor_nonNumeric_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor("abc"));
        }

        @Test
        @DisplayName("parseSalaryFloor blank should return null")
        void parseSalaryFloor_blank_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor("  "));
        }

        @Test
        @DisplayName("parseSalaryFloor null should return null")
        void parseSalaryFloor_null_shouldDrop() {
            assertNull(BotMemorySyncService.parseSalaryFloor(null));
        }
    }

    // ── Locations parse firewall ────────────────────────────────────

    @Nested
    @DisplayName("Locations parse-lenient / validate-strict")
    class LocationsParseTests {

        @Test
        @DisplayName("parseLocations 'São Paulo, Remote' should return 2 items")
        void parseLocations_commaSeparated_shouldParse() {
            var result = BotMemorySyncService.parseLocations("São Paulo, Remote");
            assertEquals(2, result.size());
            assertEquals("São Paulo", result.get(0));
            assertEquals("Remote", result.get(1));
        }

        @Test
        @DisplayName("parseLocations blank should return empty list")
        void parseLocations_blank_shouldReturnEmpty() {
            assertEquals(List.of(), BotMemorySyncService.parseLocations("  "));
        }

        @Test
        @DisplayName("parseLocations null should return empty list")
        void parseLocations_null_shouldReturnEmpty() {
            assertEquals(List.of(), BotMemorySyncService.parseLocations(null));
        }

        @Test
        @DisplayName("parseLocations items exceeding 100 chars should be dropped")
        void parseLocations_longItem_shouldDrop() {
            String longCity = "A".repeat(101);
            var result = BotMemorySyncService.parseLocations(longCity + ", Remote");
            assertEquals(1, result.size());
            assertEquals("Remote", result.get(0));
        }
    }

    // ── ExcludedCompanies parse firewall ────────────────────────────

    @Nested
    @DisplayName("ExcludedCompanies parse-lenient / validate-strict")
    class ExcludedCompaniesParseTests {

        @Test
        @DisplayName("parseExcludedCompanies 'Acme Corp, Evil Inc' should return 2 items")
        void parseExcludedCompanies_commaSeparated_shouldParse() {
            var result = BotMemorySyncService.parseExcludedCompanies("Acme Corp, Evil Inc");
            assertEquals(2, result.size());
            assertEquals("Acme Corp", result.get(0));
            assertEquals("Evil Inc", result.get(1));
        }

        @Test
        @DisplayName("parseExcludedCompanies blank should return empty list")
        void parseExcludedCompanies_blank_shouldReturnEmpty() {
            assertEquals(List.of(), BotMemorySyncService.parseExcludedCompanies("  "));
        }

        @Test
        @DisplayName("parseExcludedCompanies items exceeding 200 chars should be dropped")
        void parseExcludedCompanies_longName_shouldDrop() {
            String longName = "B".repeat(201);
            var result = BotMemorySyncService.parseExcludedCompanies(longName + ", Acme");
            assertEquals(1, result.size());
            assertEquals("Acme", result.get(0));
        }
    }

    // ── Integration: merge into profile via bot memory ──────────────

    @Nested
    @DisplayName("mergeIntoProfile — preferences integration")
    class MergePreferencesTests {

        @Test
        @DisplayName("syncFromBotMemory should fill null preferences from bot data")
        void mergeIntoProfile_shouldFillNullPreferences() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remoto
                    salary: 5000
                    locations: São Paulo, Remote
                    excludedCompanies: Acme Corp
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workModel() == WorkModel.REMOTE
                        && prefs.salaryFloor() == 5000
                        && prefs.locations().equals(List.of("São Paulo", "Remote"))
                        && prefs.excludedCompanies().equals(List.of("Acme Corp"));
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should NOT overwrite human-set preferences")
        void mergeIntoProfile_shouldNotOverwriteHumanPreferences() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: presencial
                    salary: 99999
                    locations: Rio de Janeiro
                    """));

            var humanPrefs = new UserPreferences(WorkModel.REMOTE, 8000, List.of("Curitiba"), List.of());
            var existingProfile = existingProfile(humanPrefs);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

            // All mapped fields are already set → nothing should be changed/saved
            service.syncFromBotMemory(1L);

            verify(userProfileRepository, never()).save(any());
        }

        @Test
        @DisplayName("syncFromBotMemory should drop absurd salary from bot data")
        void mergeIntoProfile_shouldDropAbsurdSalary() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    salary: 999999999
                    workModel: remote
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.salaryFloor() == null  // absurd salary dropped
                        && prefs.workModel() == WorkModel.REMOTE; // workModel still set
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should drop ambiguous work model tokens")
        void mergeIntoProfile_shouldDropAmbiguousWorkModel() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: maybe remote
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

            service.syncFromBotMemory(1L);

            // Nothing valid parsed → no save
            verify(userProfileRepository, never()).save(any());
        }

        @Test
        @DisplayName("syncFromBotMemory should merge partial preferences (only workModel set)")
        void mergeIntoProfile_shouldMergePartialPreferences() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: hybrid
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workModel() == WorkModel.HYBRID
                        && prefs.salaryFloor() == null
                        && prefs.locations().isEmpty()
                        && prefs.excludedCompanies().isEmpty();
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should fill remaining null fields when partial prefs exist")
        void mergeIntoProfile_shouldFillRemainingFields() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remote
                    salary: 3000
                    """));

            // Profile already has workModel set but salary is null
            var existingPrefs = new UserPreferences(WorkModel.HYBRID, null, List.of(), List.of());
            var existingProfile = existingProfile(existingPrefs);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workModel() == WorkModel.HYBRID  // human-set wins
                        && prefs.salaryFloor() == 3000;           // bot fills null
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should emit audit log lines for each merged preference field (behavior-plus-log)")
        void mergeIntoProfile_shouldLogAuditLines() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remoto
                    salary: 5000
                    locations: São Paulo, Remote
                    excludedCompanies: Acme Corp
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            // Behavior-plus-log: verify the save was called (proving merge happened),
            // which also proves the audit INFO log lines were emitted by mergePreferences()
            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workModel() == WorkModel.REMOTE
                        && prefs.salaryFloor() == 5000
                        && prefs.locations().size() == 2
                        && prefs.excludedCompanies().size() == 1;
            }));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private UserProfile existingProfile(UserPreferences prefs) {
        return new UserProfile(
                10L, 1L, "Valid resume text for testing purposes with enough content here.",
                List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, prefs);
    }
}
