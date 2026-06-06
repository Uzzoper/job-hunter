package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.List;

public interface UserProfileUseCase {
    UserProfile getProfile(Long userId);

    UserProfile saveProfile(Long userId, String resumeText, List<String> skills, CompanyTone tone);
}
