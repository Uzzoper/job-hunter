package com.juanperuzzo.job_hunter.application.port.out;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Outbound port for reading and writing the Hermes bot memory files
 * ({@code MEMORY.md}, {@code USER.md}). Implementations handle the filesystem
 * details; the application layer uses only this abstraction.
 */
public interface BotMemoryPort {

    /**
     * Reads the full content of the file at {@code path}.
     *
     * @return the file content, or {@link Optional#empty()} if the file does not exist
     */
    Optional<String> readFile(Path path);

    /**
     * Appends a new section to the file at {@code path}. The section text should
     * already be formatted (e.g. preceded by a {@code §} delimiter). If the file
     * does not exist it is created (including parent directories).
     *
     * @throws com.juanperuzzo.job_hunter.domain.exception.BotMemorySyncException
     *         if the write fails
     */
    void appendSection(Path path, String sectionText);
}
