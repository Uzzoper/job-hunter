package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService tests")
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileService(userRepository, userProfileRepository);
    }

    @Test
    @DisplayName("getProfile should return blank template when user has no profile")
    void getProfile_whenProfileDoesNotExist_shouldReturnBlankTemplate() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        var profile = userProfileService.getProfile(1L);

        assertEquals(1L, profile.userId());
        assertEquals("", profile.resumeText());
        assertTrue(profile.skills().isEmpty());
        assertEquals(CompanyTone.STARTUP, profile.tone());
    }

    @Test
    @DisplayName("saveProfile should create profile when none exists")
    void saveProfile_whenProfileDoesNotExist_shouldCreateProfile() {
        var resume = validResume();
        var input = new UserProfile(null, 1L, resume, List.of("Java", "Spring"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);
        var saved = new UserProfile(10L, 1L, resume, List.of("Java", "Spring"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileRepository.save(input)).thenReturn(saved);

        var profile = userProfileService.saveProfile(1L, input);

        assertEquals(saved, profile);
    }

    @Test
    @DisplayName("saveProfile should update existing profile preserving profile id")
    void saveProfile_whenProfileExists_shouldUpdateProfile() {
        var resume = validResume();
        var existing = new UserProfile(10L, 1L, "Old resume with enough content to be valid for this test.",
                List.of("Java"), CompanyTone.CASUAL, List.of(), null, null, null, null, null);
        var input = new UserProfile(null, 1L, resume, List.of("Java", "PostgreSQL"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null);
        var saved = new UserProfile(10L, 1L, resume, List.of("Java", "PostgreSQL"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
        when(userProfileRepository.save(new UserProfile(10L, 1L, resume, List.of("Java", "PostgreSQL"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null)))
                .thenReturn(saved);

        var profile = userProfileService.saveProfile(1L, input);

        assertEquals(saved, profile);
    }

    @Test
    @DisplayName("saveProfile should throw IllegalArgumentException when resume is too short")
    void saveProfile_whenResumeIsTooShort_shouldThrowIllegalArgumentException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        var input = new UserProfile(null, 1L, "short", List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> userProfileService.saveProfile(1L, input));
    }

    @Test
    @DisplayName("getProfile should throw UserNotFoundException when user does not exist")
    void getProfile_whenUserDoesNotExist_shouldThrowUserNotFoundException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userProfileService.getProfile(99L));
    }

    @Test
    @DisplayName("saveProfile should persist contact fields")
    void saveProfile_shouldPersistContactFields() {
        var resume = validResume();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        var input = new UserProfile(null, 1L, resume, List.of("Java"), CompanyTone.FORMAL, List.of(),
                "(42) 99999-0000", "me@example.com", "https://me.dev",
                "https://github.com/me", "https://linkedin.com/in/me");
        var saved = new UserProfile(10L, 1L, resume, List.of("Java"), CompanyTone.FORMAL, List.of(),
                "(42) 99999-0000", "me@example.com", "https://me.dev",
                "https://github.com/me", "https://linkedin.com/in/me");
        when(userProfileRepository.save(input)).thenReturn(saved);

        var profile = userProfileService.saveProfile(1L, input);

        assertEquals("(42) 99999-0000", profile.phone());
        assertEquals("me@example.com", profile.contactEmail());
        assertEquals("https://me.dev", profile.portfolioUrl());
        assertEquals("https://github.com/me", profile.githubUrl());
        assertEquals("https://linkedin.com/in/me", profile.linkedinUrl());
    }

    @Test
    @DisplayName("getProfile should return stored contact fields")
    void getProfile_shouldReturnContactFields() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        var stored = new UserProfile(10L, 1L, validResume(), List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, "stored@example.com", "https://stored.dev", null, null);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));

        var profile = userProfileService.getProfile(1L);

        assertEquals("stored@example.com", profile.contactEmail());
        assertEquals("https://stored.dev", profile.portfolioUrl());
        assertNull(profile.githubUrl());
    }

    private User user() {
        return new User(1L, "juan@example.com", "Juan", "$2a$hash");
    }

    private String validResume() {
        return "Experienced Java developer with Spring Boot, PostgreSQL, Docker, REST APIs, and frontend skills.";
    }
}
