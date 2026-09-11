package com.juanperuzzo.job_hunter.infrastructure.botmemory;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.domain.exception.BotMemorySyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Filesystem-backed implementation of {@link BotMemoryPort}. Uses
 * {@link java.nio.file.Files} for all I/O — no Spring dependencies beyond the
 * {@code @Component} annotation (wired via {@code AppConfig}).
 */
public class FileSystemBotMemoryAdapter implements BotMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBotMemoryAdapter.class);

    @Override
    public Optional<String> readFile(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            throw new BotMemorySyncException("Failed to read bot memory file: " + path, e);
        }
    }

    @Override
    public void appendSection(Path path, String sectionText) {
        try {
            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Append the section text (create file if absent)
            Files.writeString(path, sectionText,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            log.debug("Appended section to {}", path);
        } catch (IOException e) {
            throw new BotMemorySyncException("Failed to write to bot memory file: " + path, e);
        }
    }
}
