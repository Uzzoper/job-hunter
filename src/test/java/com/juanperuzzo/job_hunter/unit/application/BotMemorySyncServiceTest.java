package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotMemorySyncService tests")
class BotMemorySyncServiceTest {

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

    // ── Parsing ───────────────────────────────────────────────────────

    @Test
    @DisplayName("parseMemoryContent should extract key-value pairs and raw sections from §-delimited content")
    void readMemoryFile_shouldParsePreferences() {
        String content = """
                §
                Locations: City X (remote only). Discard onsite/hybrid in City Y.
                §
                Salary: Z range
                §
                Some free-form notes and opinions from past interactions
                """;

        var prefs = service.parseMemoryContent(content);

        assertEquals(2, prefs.keyValues().size());
        assertEquals("City X (remote only). Discard onsite/hybrid in City Y.", prefs.keyValues().get("Locations"));
        assertEquals("Z range", prefs.keyValues().get("Salary"));
        assertEquals(3, prefs.rawSections().size());
    }

    @Test
    @DisplayName("parseMemoryContent should return empty BotPreferences for blank content")
    void parseContent_whenBlank_shouldReturnEmptyPreferences() {
        var prefs = service.parseMemoryContent("");

        assertTrue(prefs.keyValues().isEmpty());
        assertTrue(prefs.rawSections().isEmpty());
    }

    @Test
    @DisplayName("parseMemoryContent should return empty BotPreferences for null content")
    void parseContent_whenNull_shouldReturnEmptyPreferences() {
        var prefs = service.parseMemoryContent(null);

        assertTrue(prefs.keyValues().isEmpty());
        assertTrue(prefs.rawSections().isEmpty());
    }

    @Test
    @DisplayName("readMemoryFile when duplicate keys across sections should use last value")
    void readMemoryFile_whenDuplicateKeys_shouldUseLastValue() {
        String content = """
                §
                Locations: City A
                §
                Locations: City B (updated)
                """;

        var prefs = service.parseMemoryContent(content);

        assertEquals(1, prefs.keyValues().size());
        assertEquals("City B (updated)", prefs.keyValues().get("Locations"));
    }

    // ── Write-back ────────────────────────────────────────────────────

    @Test
    @DisplayName("writeMemoryEntry should append §-delimited section to MEMORY.md")
    void writeMemoryEntry_shouldAppendToFile() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");

        service.writeMemoryEntry(1L, "Draft rejected: too generic for Company Z");

        verify(botMemoryPort).appendSection(eq(memoryFile), contains("Draft rejected"));
    }

    @Test
    @DisplayName("writeMemoryEntry when text contains § delimiter should escape it so re-parsing stays intact")
    void writeMemoryEntry_whenTextContainsDelimiter_shouldNotBreakParse() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        service.writeMemoryEntry(1L, "Draft rejected § due to delimiter § in reason");

        verify(botMemoryPort).appendSection(eq(memoryFile), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();

        // Escaped with middle dot
        assertTrue(payload.contains("\u00B7"));
        // No raw § inside the section body (only the leading delimiter)
        assertFalse(payload.contains("§ due to"));

        // Re-parsing yields exactly one intact section with the escaped body
        var prefs = service.parseMemoryContent(payload);
        assertEquals(1, prefs.rawSections().size());
        assertEquals("Draft rejected \u00B7 due to delimiter \u00B7 in reason",
                prefs.rawSections().get(0));
    }

    // ── Missing file ──────────────────────────────────────────────────

    @Test
    @DisplayName("syncFromBotMemory when memory file missing should not throw")
    void readMemoryFile_whenFileMissing_shouldReturnEmpty() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.empty());

        // Should not throw
        service.syncFromBotMemory(1L);
    }

    // ── Merge ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("syncFromBotMemory should fill empty profile fields from parsed keyValues")
    void syncFromBotMemory_shouldFillEmptyProfileFields() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                §
                contactEmail: bot-learned@example.com
                phone: +55 42 99999-0000
                """));

        var existingProfile = existingProfile(null, null);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncFromBotMemory(1L);

        verify(userProfileRepository).save(argThat(profile ->
                "bot-learned@example.com".equals(profile.contactEmail())
                        && "+55 42 99999-0000".equals(profile.phone())
        ));
    }

    @Test
    @DisplayName("syncFromBotMemory should not overwrite existing profile fields")
    void syncFromBotMemory_shouldNotOverwriteExistingFields() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                §
                contactEmail: bot-learned@example.com
                phone: +55 42 99999-0000
                """));

        var existingProfile = existingProfile("existing@example.com", "+55 11 88888-0000");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

        // All mapped fields are already set → nothing should be changed/saved
        service.syncFromBotMemory(1L);

        verify(userProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("syncFromBotMemory when profile does not exist should skip merge without error")
    void syncFromBotMemory_whenProfileNotFound_shouldSkipGracefully() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                §
                contactEmail: bot-learned@example.com
                """));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // Should not throw
        service.syncFromBotMemory(1L);

        verify(userProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("syncFromBotMemory when no key-values match profile fields should not call save")
    void syncFromBotMemory_whenNoMatchingKeys_shouldNotSave() {
        Path memoryFile = tempDir.resolve("memories/MEMORY.md");
        when(botMemoryPort.readFile(memoryFile)).thenReturn(Optional.of("""
                §
                Locations: City X
                Salary: Z range
                """));

        var existingProfile = existingProfile(null, null);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));

        service.syncFromBotMemory(1L);

        verify(userProfileRepository, never()).save(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private UserProfile existingProfile(String contactEmail, String phone) {
        return new UserProfile(
                10L, 1L, "Valid resume text for testing purposes with enough content here.",
                List.of("Java"), CompanyTone.STARTUP, List.of(),
                phone, contactEmail, null, null, null);
    }
}
