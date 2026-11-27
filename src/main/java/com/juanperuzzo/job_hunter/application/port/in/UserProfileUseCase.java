package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.UserProfile;

public interface UserProfileUseCase {
    UserProfile getProfile();

    UserProfile saveProfile(UserProfile profile);
}
