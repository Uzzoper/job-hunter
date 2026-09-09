package com.juanperuzzo.job_hunter.infrastructure.botmemory;

import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
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
 * {@link BotMemorySyncService#syncFromBotMemory(Long)} for every registered user,
 * so preferences recorded in the bot chat are respected on the next backend run.
 * <p>
 * Missing files/directories produce a WARN and startup continues — the backend must
 * never fail to boot because the bot profile is absent (e.g. a dev machine with no
 * local Hermes bot). The same applies per user: a failed sync is logged as a WARN
 * and the remaining users are still processed. The sync stays synchronous and simple
 * (no {@code @Async}).
 */
public class BotMemoryStartupSync {

    private static final Logger log = LoggerFactory.getLogger(BotMemoryStartupSync.class);

    private final BotMemorySyncService botMemorySyncService;
    private final UserRepository userRepository;
    private final Path memoryDir;

    public BotMemoryStartupSync(BotMemorySyncService botMemorySyncService,
                                UserRepository userRepository,
                                Path memoryDir) {
        this.botMemorySyncService = botMemorySyncService;
        this.userRepository = userRepository;
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
        if (!Files.isRegularFile(memoryFile)) {
            log.warn("Bot memory file '{}' not found — skipping. Startup continues.", memoryFile);
            return;
        }

        var users = userRepository.findAll();
        if (users.isEmpty()) {
            log.debug("No registered users — skipping bot memory sync at startup.");
            return;
        }

        for (var user : users) {
            try {
                botMemorySyncService.syncFromBotMemory(user.id());
            } catch (Exception e) {
                log.warn("Bot memory sync failed for user {} at startup — continuing: {}",
                        user.id(), e.getMessage());
            }
        }
    }
}