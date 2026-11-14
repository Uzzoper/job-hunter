package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.ResumeRetrievalUseCase;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.domain.exception.ResumeNotFoundException;

/**
 * Serves the stored original resume PDF (issue #92 slice a). Absence is an
 * expected, mapped outcome ({@link ResumeNotFoundException} → 404), not an
 * error.
 */
public class ResumeRetrievalService implements ResumeRetrievalUseCase {

    private final ResumeStorage resumeStorage;

    public ResumeRetrievalService(ResumeStorage resumeStorage) {
        this.resumeStorage = resumeStorage;
    }

    @Override
    public byte[] downloadResume(Long userId) {
        return resumeStorage.read(userId)
                .orElseThrow(() -> new ResumeNotFoundException("No resume stored for user: " + userId));
    }
}
