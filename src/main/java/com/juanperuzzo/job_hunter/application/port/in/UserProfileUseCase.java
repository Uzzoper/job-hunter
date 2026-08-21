package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.UserProfile;

public interface UserProfileUseCase {
    UserProfile getProfile(Long userId);

    UserProfile saveProfile(Long userId, UserProfile profile);
}
