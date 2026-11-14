package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Retrieves the user's currently stored original resume PDF (issue #92
 * slice a) — the authenticated counterpart of {@code upload-resume}, so
 * clients (bot or user) can stage the canonical artifact instead of keeping
 * a hand-maintained local copy.
 */
public interface ResumeRetrievalUseCase {

    /**
     * @return the stored PDF bytes for the given user
     * @throws com.juanperuzzo.job_hunter.domain.exception.ResumeNotFoundException
     *         when no PDF is stored for that user
     */
    byte[] downloadResume(Long userId);
}
