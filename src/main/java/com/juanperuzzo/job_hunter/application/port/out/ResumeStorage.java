package com.juanperuzzo.job_hunter.application.port.out;

import java.util.Optional;

/**
 * Filesystem storage for the user's original resume PDF (issue #92 slice a).
 * The adapter owns the path convention `{uploadDir}/{userId}/resume.pdf` —
 * the application layer never touches file paths. Files are capped at 2 MB
 * by the upload flow, so bytes-in-memory is an appropriate contract.
 */
public interface ResumeStorage {

    /** @return the stored PDF bytes, or empty when the user has no stored resume */
    Optional<byte[]> read(Long userId);

    /** Stores (or replaces) the user's resume PDF. */
    void write(Long userId, byte[] pdf);
}
