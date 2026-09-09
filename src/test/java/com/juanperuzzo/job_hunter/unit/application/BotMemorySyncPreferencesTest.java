package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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

    // ── WorkPreference parse firewall ────────────────────────────────

    @Nested
    @DisplayName("WorkPreference parse-lenient / validate-strict")
    class WorkPreferenceParseTests {

        @Test
        @DisplayName("parseWorkPreference 'remoto' should return Remote")
        void parseWorkPreference_remoto_shouldReturnRemote() {
            assertEquals(new WorkPreference.Remote(),
                    BotMemorySyncService.parseWorkPreference("remoto", List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference 'remote' with a city list should still return Remote (cities are unrepresentable for Remote)")
        void parseWorkPreference_remote_ignoresCityList() {
            assertEquals(new WorkPreference.Remote(),
                    BotMemorySyncService.parseWorkPreference("remote", List.of("Curitiba")));
        }

        @Test
        @DisplayName("parseWorkPreference 'Híbrido' with accent and cities should return Hybrid")
        void parseWorkPreference_hibrido_shouldReturnHybrid() {
            assertEquals(new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo")),
                    BotMemorySyncService.parseWorkPreference("Híbrido", List.of("Curitiba", "São Paulo")));
        }

        @Test
        @DisplayName("parseWorkPreference 'hybrid' with cities should return Hybrid")
        void parseWorkPreference_hybrid_shouldReturnHybrid() {
            assertEquals(new WorkPreference.Hybrid(List.of("Curitiba")),
                    BotMemorySyncService.parseWorkPreference("hybrid", List.of("Curitiba")));
        }

        @Test
        @DisplayName("parseWorkPreference 'hibrido' without cities should return null (unrepresentable, never invented)")
        void parseWorkPreference_hybridWithoutCities_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("hibrido", List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference 'hybrid' with null cities should return null")
        void parseWorkPreference_hybridWithNullCities_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("hybrid", null));
        }

        @Test
        @DisplayName("parseWorkPreference 'presencial' with cities should return Onsite")
        void parseWorkPreference_presencial_shouldReturnOnsite() {
            assertEquals(new WorkPreference.Onsite(List.of("São Paulo")),
                    BotMemorySyncService.parseWorkPreference("presencial", List.of("São Paulo")));
        }

        @Test
        @DisplayName("parseWorkPreference 'on-site' with cities should return Onsite")
        void parseWorkPreference_onsite_shouldReturnOnsite() {
            assertEquals(new WorkPreference.Onsite(List.of("Curitiba")),
                    BotMemorySyncService.parseWorkPreference("on-site", List.of("Curitiba")));
        }

        @Test
        @DisplayName("parseWorkPreference 'on site' with cities should return Onsite")
        void parseWorkPreference_onsiteSpaced_shouldReturnOnsite() {
            assertEquals(new WorkPreference.Onsite(List.of("Curitiba")),
                    BotMemorySyncService.parseWorkPreference("on site", List.of("Curitiba")));
        }

        @Test
        @DisplayName("parseWorkPreference 'presencial' without cities should return null (unrepresentable)")
        void parseWorkPreference_onsiteWithoutCities_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("presencial", List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference 'maybe remote' should return null (ambiguous)")
        void parseWorkPreference_ambiguousPhrase_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("maybe remote", List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference 'banana' should return null (garbage)")
        void parseWorkPreference_garbage_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("banana", List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference 'I work from home' should return null")
        void parseWorkPreference_freeText_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("I work from home", List.of("Curitiba")));
        }

        @Test
        @DisplayName("parseWorkPreference null should return null")
        void parseWorkPreference_null_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference(null, List.of()));
        }

        @Test
        @DisplayName("parseWorkPreference blank should return null")
        void parseWorkPreference_blank_shouldDrop() {
            assertNull(BotMemorySyncService.parseWorkPreference("  ", List.of()));
        }
    }

    // ── Salary parse firewall (unchanged) ────────────────────────────

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

    // ── Cities parse firewall ────────────────────────────────────────

    @Nested
    @DisplayName("Cities parse-lenient / validate-strict")
    class CitiesParseTests {

        @Test
        @DisplayName("parseCities 'São Paulo, Curitiba' should return 2 items")
        void parseCities_commaSeparated_shouldParse() {
            var result = BotMemorySyncService.parseCities("São Paulo, Curitiba");
            assertEquals(2, result.size());
            assertEquals("São Paulo", result.get(0));
            assertEquals("Curitiba", result.get(1));
        }

        @Test
        @DisplayName("parseCities blank should return empty list")
        void parseCities_blank_shouldReturnEmpty() {
            assertEquals(List.of(), BotMemorySyncService.parseCities("  "));
        }

        @Test
        @DisplayName("parseCities null should return empty list")
        void parseCities_null_shouldReturnEmpty() {
            assertEquals(List.of(), BotMemorySyncService.parseCities(null));
        }

        @Test
        @DisplayName("parseCities items exceeding 100 chars should be dropped")
        void parseCities_longItem_shouldDrop() {
            String longCity = "A".repeat(101);
            var result = BotMemorySyncService.parseCities(longCity + ", Curitiba");
            assertEquals(1, result.size());
            assertEquals("Curitiba", result.get(0));
        }

        @Test
        @DisplayName("parseCities when exceeding 20 items should trim to 20")
        void parseCities_whenExceeding20_shouldTrim() {
            var many = new java.util.ArrayList<String>();
            for (int i = 0; i < 25; i++) many.add("City" + i);
            var result = BotMemorySyncService.parseCities(String.join(",", many));
            assertEquals(20, result.size());
        }
    }

    // ── ExcludedCompanies parse firewall (unchanged) ─────────────────

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
        @DisplayName("syncFromBotMemory should fill null preferences from bot data (hybrid + cities)")
        void mergeIntoProfile_shouldFillNullPreferences() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: hibrido
                    salary: 5000
                    locations: Curitiba, São Paulo
                    excludedCompanies: Acme Corp
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo")).equals(prefs.workPreference())
                        && prefs.salaryFloor() == 5000
                        && prefs.excludedCompanies().equals(List.of("Acme Corp"));
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should fill a Remote preference (cities dropped — unrepresentable for Remote)")
        void mergeIntoProfile_shouldFillRemotePreference() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remoto
                    salary: 8000
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workPreference() instanceof WorkPreference.Remote
                        && prefs.salaryFloor() == 8000;
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

            var humanPrefs = new UserPreferences(
                    new WorkPreference.Remote(), 8000, List.of("Acme"));
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
                        && prefs.workPreference() instanceof WorkPreference.Remote; // workPreference still set
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should drop ambiguous work model tokens")
        void mergeIntoProfile_shouldDropAmbiguousWorkModel() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: maybe remote
                    locations: Curitiba
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

            service.syncFromBotMemory(1L);

            // Nothing valid parsed → no save
            verify(userProfileRepository, never()).save(any());
        }

        @Test
        @DisplayName("syncFromBotMemory should drop hybrid without cities (unrepresentable — anti-hallucination)")
        void mergeIntoProfile_shouldDropHybridWithoutCities() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: hibrido
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

            service.syncFromBotMemory(1L);

            // Hybrid without cities cannot be represented → no save
            verify(userProfileRepository, never()).save(any());
        }

        @Test
        @DisplayName("syncFromBotMemory should ignore locations for Remote (remote implies no city constraint)")
        void mergeIntoProfile_shouldIgnoreLocationsForRemote() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remoto
                    locations: Curitiba
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workPreference() instanceof WorkPreference.Remote
                        // locations line produced no city-carrying variant
                        && !(prefs.workPreference() instanceof WorkPreference.Hybrid)
                        && !(prefs.workPreference() instanceof WorkPreference.Onsite);
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should merge partial preferences (only workPreference set)")
        void mergeIntoProfile_shouldMergePartialPreferences() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: remote
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && prefs.workPreference() instanceof WorkPreference.Remote
                        && prefs.salaryFloor() == null
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

            // Profile already has workPreference set but salary is null
            var existingPrefs = new UserPreferences(
                    new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
            var existingProfile = existingProfile(existingPrefs);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.syncFromBotMemory(1L);

            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && new WorkPreference.Hybrid(List.of("Curitiba")).equals(prefs.workPreference()) // human-set wins
                        && prefs.salaryFloor() == 3000;           // bot fills null
            }));
        }

        @Test
        @DisplayName("syncFromBotMemory should emit INFO audit lines for each merged preference field (real log capture)")
        void mergeIntoProfile_shouldLogAuditLines() {
            Path memoryFile = tempDir.resolve("memories/MEMORY.md");
            when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                    §
                    workModel: hibrido
                    salary: 5000
                    locations: São Paulo, Curitiba
                    excludedCompanies: Acme Corp
                    """));

            var existingProfile = existingProfile(null);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
            when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Attach a real logback appender to the service logger to prove the
            // audit lines promised by docs/specs/user-preferences.md §Audit trail
            // are actually emitted, not just conceptually part of the merge.
            var logger = (Logger) LoggerFactory.getLogger(BotMemorySyncService.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                service.syncFromBotMemory(1L);

                var messages = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();
                assertTrue(messages.stream().anyMatch(m -> m.contains("workPreference=null→Hybrid")),
                        "expected an audit line for workPreference fill, got: " + messages);
                assertTrue(messages.stream().anyMatch(m -> m.contains("salaryFloor=null→5000")),
                        "expected an audit line for salaryFloor fill, got: " + messages);
                assertTrue(messages.stream().anyMatch(m -> m.contains("excludedCompanies=null→[Acme Corp]")),
                        "expected an audit line for excludedCompanies fill, got: " + messages);
                assertTrue(messages.stream().anyMatch(m -> m.contains("Merged bot memory values into profile for user 1")),
                        "expected the final merge confirmation line, got: " + messages);

                // The per-field audit lines must be INFO level (filter out the
                // expected WARN for the missing USER.md file).
                var workPreferenceEvent = appender.list.stream()
                        .filter(e -> e.getFormattedMessage().contains("workPreference=null→Hybrid"))
                        .findFirst().orElseThrow();
                assertEquals(Level.INFO, workPreferenceEvent.getLevel());
            } finally {
                logger.detachAppender(appender);
            }

            // Behavior-plus-log: the merge still happened
            verify(userProfileRepository).save(argThat(profile -> {
                var prefs = profile.preferences();
                return prefs != null
                        && new WorkPreference.Hybrid(List.of("São Paulo", "Curitiba")).equals(prefs.workPreference())
                        && prefs.salaryFloor() == 5000
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