package com.juanperuzzo.job_hunter.infrastructure.botmemory;

import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup trigger for bot memory sync. After the app is fully ready, it verifies
 * that the bot memory directory is reachable and then runs
 * {@link BotMemorySyncService#syncFromBotMemory()} once (single-owner, self-hosted).
 * <p>
 * Missing files/directories produce a WARN and startup continues — the backend must
 * never fail to boot because the bot profile is absent (e.g. a dev machine with no
 * local Hermes bot).
 */
public class BotMemoryStartupSync {

    private static final Logger log = LoggerFactory.getLogger(BotMemoryStartupSync.class);

    private final BotMemorySyncService botMemorySyncService;
    private final Path memoryDir;

    public BotMemoryStartupSync(BotMemorySyncService botMemorySyncService, Path memoryDir) {
        this.botMemorySyncService = botMemorySyncService;
        this.memoryDir = memoryDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Bot memory sync check at startup: {}", memoryDir);
        if (!Files.isDirectory(memoryDir)) {
            log.warn("Bot memory directory '{}' does not exist — skipping bot memory sync. "
                    + "Startup continues.", memoryDir);
            return;
        }

        Path memoryFile = memoryDir.resolve("memories/MEMORY.md");
        Path userFile = memoryDir.resolve("memories/USER.md");
        if (!Files.isRegularFile(memoryFile) && !Files.isRegularFile(userFile)) {
            log.warn("Neither bot memory file '{}' nor '{}' found — skipping. Startup continues.",
                    memoryFile, userFile);
            return;
        }

        try {
            botMemorySyncService.syncFromBotMemory();
        } catch (Exception e) {
            log.warn("Bot memory sync failed at startup — continuing: {}", e.getMessage());
        }
    }
}