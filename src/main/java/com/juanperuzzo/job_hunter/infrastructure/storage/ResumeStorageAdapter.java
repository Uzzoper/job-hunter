package com.juanperuzzo.job_hunter.infrastructure.storage;

import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Filesystem adapter for the original resume PDF (issue #92 slice a): the
 * single owner of the path convention `{uploadDir}/resume.pdf`
 * established by the resume-upload spec. Both the upload write path and the
 * retrieval read path resolve files through this adapter.
 *
 * <p>Legacy fallback: files written by the pre-self-hosted refactor live at
 * `{uploadDir}/{userId}/resume.pdf` and are still readable. When multiple legacy
 * files exist, the former owner ID setting identifies the operator's file.
 */
public class ResumeStorageAdapter implements ResumeStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorageAdapter.class);

    private final Path uploadDir;
    private final String legacyOwnerUserId;

    public ResumeStorageAdapter(String uploadDir) {
        this(uploadDir, null);
    }

    public ResumeStorageAdapter(String uploadDir, String legacyOwnerUserId) {
        this.uploadDir = Path.of(uploadDir);
        this.legacyOwnerUserId = normalizeLegacyOwnerUserId(legacyOwnerUserId);
    }

    @Override
    public Optional<byte[]> read() {
        var file = resumePath();
        if (!Files.exists(file)) {
            var legacy = findLegacyResumePath();
            if (legacy.isEmpty()) {
                return Optional.empty();
            }
            file = legacy.orElseThrow();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored resume", e);
        }
    }

    @Override
    public void write(byte[] pdf) {
        try {
            var path = resumePath();
            Files.createDirectories(path.getParent());
            Files.write(path, pdf);
            log.info("Stored uploaded resume at {}", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store resume", e);
        }
    }

    /** Single owner of the `{uploadDir}/resume.pdf` convention. */
    private Path resumePath() {
        return uploadDir.resolve("resume.pdf");
    }

    private Optional<Path> findLegacyResumePath() {
        if (legacyOwnerUserId != null) {
            var ownerResume = uploadDir.resolve(legacyOwnerUserId).resolve("resume.pdf");
            return Files.isRegularFile(ownerResume) ? Optional.of(ownerResume) : Optional.empty();
        }
        if (!Files.isDirectory(uploadDir)) {
            return Optional.empty();
        }

        try (var entries = Files.list(uploadDir)) {
            List<Path> resumes = entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("[1-9]\\d*"))
                    .map(path -> path.resolve("resume.pdf"))
                    .filter(Files::isRegularFile)
                    .limit(2)
                    .toList();
            if (resumes.size() > 1) {
                throw new IllegalStateException("Multiple legacy resume files found; set bot.service.owner-user-id "
                        + "or BOT_SERVICE_OWNER_USER_ID "
                        + "to select the operator's resume");
            }
            return resumes.stream().findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to locate legacy resume", e);
        }
    }

    private static String normalizeLegacyOwnerUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(userId.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException("The legacy owner ID must be a positive integer");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The legacy owner ID must be a positive integer", e);
        }
    }
}
