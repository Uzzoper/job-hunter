package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumePreview;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stateless resume preview (issue #92 slice b): extracts and returns what a
 * commit would write, against the stored profile — persists nothing.
 */
public class ResumePreviewService {

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final int maxAiChars;

    public ResumePreviewService(AiPort aiPort, UserProfileService userProfileService,
                                UserProfileRepository userProfileRepository, int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.maxAiChars = maxAiChars;
    }

    /**
     * @param userId the authenticated user's ID
     * @param file   the uploaded PDF file (same validation as the upload flow)
     * @return the preview of what commit would write; nothing is persisted
     */
    public ResumePreview preview(Long userId, MultipartFile file) {
        throw new UnsupportedOperationException("slice (b): not implemented");
    }
}
