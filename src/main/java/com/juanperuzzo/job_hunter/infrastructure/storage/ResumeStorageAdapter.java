package com.juanperuzzo.job_hunter.infrastructure.storage;

import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Filesystem adapter for the original resume PDF (issue #92 slice a): the
 * single owner of the path convention `{uploadDir}/{userId}/resume.pdf`
 * established by the resume-upload spec. Both the upload write path and the
 * retrieval read path resolve files through this adapter.
 */
public class ResumeStorageAdapter implements ResumeStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorageAdapter.class);

    private final Path uploadDir;

    public ResumeStorageAdapter(String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
    }

    @Override
    public Optional<byte[]> read(Long userId) {
        var file = resumePath(userId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored resume for user " + userId, e);
        }
    }

    @Override
    public void write(Long userId, byte[] pdf) {
        try {
            var path = resumePath(userId);
            Files.createDirectories(path.getParent());
            Files.write(path, pdf);
            log.info("Stored uploaded resume at {}", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store resume for user " + userId, e);
        }
    }

    /** Single owner of the `{uploadDir}/{userId}/resume.pdf` convention. */
    private Path resumePath(Long userId) {
        return uploadDir.resolve(String.valueOf(userId)).resolve("resume.pdf");
    }
}
