package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("UserProfileService tests")
class UserProfileServiceTest {

    @Test
    @DisplayName("get profile should return an owner template when no profile exists")
    void getProfile_whenProfileMissing_shouldReturnOwnerTemplate() {
        var repository = mock(UserProfileRepository.class);
        when(repository.find()).thenReturn(Optional.empty());

        var profile = new UserProfileService(repository).getProfile();

        assertEquals("Owner", profile.name());
        assertNull(profile.email());
        assertNull(profile.id());
    }

    @Test
    @DisplayName("save profile should preserve the stored id and preferences when omitted")
    void saveProfile_whenPreferencesOmitted_shouldPreserveIdAndPreferences() {
        var repository = mock(UserProfileRepository.class);
        var storedPreferences = new UserPreferences(null, 1000, List.of("Blocked Co"), List.of("Java"));
        var stored = profile(7L, "Old Owner", "old@example.com", "Existing resume text long enough to pass.",
                storedPreferences);
        when(repository.find()).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var incoming = profile(null, "New Owner", "new@example.com",
                "Replacement resume text long enough to pass validation.", null);

        var saved = new UserProfileService(repository).saveProfile(incoming);

        assertEquals(7L, saved.id());
        assertEquals("New Owner", saved.name());
        assertEquals("new@example.com", saved.email());
        assertEquals(storedPreferences, saved.preferences());
    }

    @Test
    @DisplayName("save profile should reject resume text shorter than the minimum")
    void saveProfile_whenResumeTooShort_shouldThrowInvalidResumeText() {
        var repository = mock(UserProfileRepository.class);
        var profile = profile(null, "Owner", null, "too short", null);

        assertThrows(InvalidResumeTextException.class,
                () -> new UserProfileService(repository).saveProfile(profile));
    }

    private static UserProfile profile(Long id, String name, String email, String resumeText,
                                       UserPreferences preferences) {
        return new UserProfile(id, name, email, resumeText, List.of("Java"), CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, preferences);
    }
}
