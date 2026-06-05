package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import java.util.Optional;

public interface UserProfileRepository {
    UserProfile save(UserProfile profile);
    Optional<UserProfile> findByUserId(Long userId);
}
