package com.juanperuzzo.job_hunter.unit.infrastructure.botmemory;

import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.infrastructure.botmemory.BotMemoryStartupSync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotMemoryStartupSync tests")
class BotMemoryStartupSyncTest {

    @Mock
    private BotMemorySyncService botMemorySyncService;

    @Mock
    private UserRepository userRepository;

    @TempDir
    Path tempDir;

    private BotMemoryStartupSync startupSync;

    @BeforeEach
    void setUp() {
        startupSync = new BotMemoryStartupSync(botMemorySyncService, userRepository, tempDir);
    }

    private void writeMemoryFile() throws IOException {
        Files.createDirectories(tempDir.resolve("memories"));
        Files.writeString(tempDir.resolve("memories/MEMORY.md"), "\n§\nphone: 123\n");
    }

    private void writeUserFile() throws IOException {
        Files.createDirectories(tempDir.resolve("memories"));
        Files.writeString(tempDir.resolve("memories/USER.md"), "\n§\nsalary: 5000\n");
    }

    @Test
    @DisplayName("onApplicationReady should invoke syncFromBotMemory for every registered user")
    void onApplicationReady_whenUsersRegistered_shouldSyncEachUser() throws IOException {
        writeMemoryFile();
        var user1 = new User(1L, "a@test.com", "Alice", "hash");
        var user2 = new User(2L, "b@test.com", "Bruno", "hash");
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));

        startupSync.onApplicationReady();

        verify(botMemorySyncService).syncFromBotMemory(1L);
        verify(botMemorySyncService).syncFromBotMemory(2L);
    }

    @Test
    @DisplayName("onApplicationReady should be a no-op when no users are registered")
    void onApplicationReady_whenNoUsers_shouldDoNothing() throws IOException {
        writeMemoryFile();
        when(userRepository.findAll()).thenReturn(List.of());

        startupSync.onApplicationReady();

        verify(userRepository).findAll();
        verify(botMemorySyncService, never()).syncFromBotMemory(any());
    }

    @Test
    @DisplayName("onApplicationReady should warn and continue when a per-user sync fails")
    void onApplicationReady_whenPerUserSyncFails_shouldWarnAndContinue() throws IOException {
        writeMemoryFile();
        var user1 = new User(1L, "a@test.com", "Alice", "hash");
        var user2 = new User(2L, "b@test.com", "Bruno", "hash");
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));
        doThrow(new RuntimeException("unreadable memory")).when(botMemorySyncService).syncFromBotMemory(1L);

        assertDoesNotThrow(startupSync::onApplicationReady);

        verify(botMemorySyncService).syncFromBotMemory(1L);
        verify(botMemorySyncService).syncFromBotMemory(2L);
    }

    @Test
    @DisplayName("onApplicationReady should warn and skip everything when the memory directory is missing")
    void onApplicationReady_whenMemoryDirMissing_shouldWarnAndSkip() {
        var sync = new BotMemoryStartupSync(botMemorySyncService, userRepository, tempDir.resolve("does-not-exist"));

        assertDoesNotThrow(sync::onApplicationReady);

        verify(userRepository, never()).findAll();
        verify(botMemorySyncService, never()).syncFromBotMemory(any());
    }

    @Test
    @DisplayName("onApplicationReady should sync when only USER.md exists (split-memory state is not dropped)")
    void onApplicationReady_whenOnlyUserFileExists_shouldSyncEachUser() throws IOException {
        writeUserFile();
        var user1 = new User(1L, "a@test.com", "Alice", "hash");
        when(userRepository.findAll()).thenReturn(List.of(user1));

        startupSync.onApplicationReady();

        // Gate is per-file, not per-MEMORY.md: USER.md alone is enough to proceed
        verify(userRepository).findAll();
        verify(botMemorySyncService).syncFromBotMemory(1L);
    }

    @Test
    @DisplayName("onApplicationReady should warn and skip when neither memory file exists")
    void onApplicationReady_whenNeitherMemoryFileExists_shouldWarnAndSkip() {
        assertDoesNotThrow(startupSync::onApplicationReady);

        verify(userRepository, never()).findAll();
        verify(botMemorySyncService, never()).syncFromBotMemory(any());
    }
}