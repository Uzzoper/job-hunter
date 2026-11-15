package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumeCorrections;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Confirmed resume commit (issue #92 slice b): the commit call IS the explicit
 * confirmation — only it replaces the active resume/profile. Re-runs the
 * extraction (documented trade-off: no draft drift, no temp files, no TOCTOU).
 */
public class ResumeCommitService {

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final ResumeStorage resumeStorage;
    private final int maxAiChars;

    public ResumeCommitService(AiPort aiPort, UserProfileService userProfileService,
                               UserProfileRepository userProfileRepository,
                               ResumeStorage resumeStorage, int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.resumeStorage = resumeStorage;
        this.maxAiChars = maxAiChars;
    }

    /**
     * @param userId      the authenticated user's ID
     * @param file        the uploaded PDF file (same validation as the upload flow)
     * @param corrections optional chat corrections; when present, {@code digest}
     *                    must match the uploaded file's md5
     * @return the saved {@link UserProfile}
     */
    public UserProfile commit(Long userId, MultipartFile file, ResumeCorrections corrections) {
        throw new UnsupportedOperationException("slice (b): not implemented");
    }
}
