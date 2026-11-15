package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.UserProfile;

public interface UserProfileUseCase {
    UserProfile getProfile(Long userId);

    /**
     * Resolves the account identity (name + login email) of the given user,
     * separately from the resume-extracted contact fields (issue #92 slice a).
     *
     * @throws com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException
     *         when the user row does not exist
     */
    AccountIdentity getAccountIdentity(Long userId);

    /**
     * Applies account-identity corrections (name and/or login email, issue #92
     * slice b) to the users row after validating them. {@code null} means "no
     * correction for this field" — the stored value is kept.
     *
     * @throws IllegalArgumentException when a provided value fails validation
     * @throws com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException
     *         when the user row does not exist
     */
    AccountIdentity updateIdentity(Long userId, String name, String email);

    UserProfile saveProfile(Long userId, UserProfile profile);
}
