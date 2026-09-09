package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.CurrentUserProvider;
import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.ProfileResponse;
import com.juanperuzzo.job_hunter.web.dto.PreferencesResponse;
import com.juanperuzzo.job_hunter.web.dto.ProjectResponse;
import com.juanperuzzo.job_hunter.web.dto.WorkPreferenceDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserProfileUseCase userProfileService;
    private final CurrentUserProvider currentUserService;
    private final ResumeUploadService resumeUploadService;

    public ProfileController(UserProfileUseCase userProfileService, CurrentUserProvider currentUserService,
                             ResumeUploadService resumeUploadService) {
        this.userProfileService = userProfileService;
        this.currentUserService = currentUserService;
        this.resumeUploadService = resumeUploadService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile() {
        Long userId = currentUserService.getCurrentUserId();
        var profile = userProfileService.getProfile(userId);
        var response = toResponse(profile);
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> saveProfile(@Valid @RequestBody ProfileRequest request) {
        Long userId = currentUserService.getCurrentUserId();
        List<Project> projects = request.projects().stream()
                .map(p -> new Project(p.name(), p.description(), String.join(", ", p.techStack())))
                .toList();
        var prefs = toUserPreferences(request.preferences());
        var profile = new UserProfile(null, userId, request.resumeText(), request.skills(),
                request.tone(), projects, request.phone(), request.contactEmail(),
                request.portfolioUrl(), request.githubUrl(), request.linkedinUrl(),
                prefs);
        var saved = userProfileService.saveProfile(userId, profile);
        var response = toResponse(saved);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload-resume")
    public ResponseEntity<ProfileResponse> uploadResume(@RequestParam("file") MultipartFile file) {
        Long userId = currentUserService.getCurrentUserId();
        var profile = resumeUploadService.uploadResume(userId, file);
        var response = toResponse(profile);
        return ResponseEntity.ok(response);
    }

    private ProfileResponse toResponse(com.juanperuzzo.job_hunter.domain.model.UserProfile profile) {
        var projectResponses = profile.projects().stream()
                .map(p -> {
                    var techStack = p.techStack().isBlank()
                            ? Collections.<String>emptyList()
                            : List.of(p.techStack().split("\\s*,\\s*"));
                    return new ProjectResponse(p.name(), p.description(), techStack);
                })
                .toList();
        var prefsResponse = toPreferencesResponse(profile.preferences());
        return new ProfileResponse(
                profile.id(), profile.userId(), profile.resumeText(),
                profile.skills(), profile.tone(), projectResponses,
                profile.phone(), profile.contactEmail(), profile.portfolioUrl(),
                profile.githubUrl(), profile.linkedinUrl(), prefsResponse);
    }

    /**
     * Converts a request-level preferences DTO into a domain {@link UserPreferences}.
     * Null input → null output (omit = preserve existing, handled by service layer).
     * The discriminated {@code workPreference} DTO is translated via
     * {@link WorkPreference} construction.
     */
    private static UserPreferences toUserPreferences(
            com.juanperuzzo.job_hunter.web.dto.PreferencesRequest req) {
        if (req == null) {
            return null;
        }
        return new UserPreferences(
                req.workPreference() != null ? req.workPreference().toDomain() : null,
                req.salaryFloor(),
                req.excludedCompanies() != null ? List.copyOf(req.excludedCompanies()) : List.of());
    }

    /**
     * Converts domain preferences into a read-only response DTO.
     * Null input → null output.
     */
    private static PreferencesResponse toPreferencesResponse(UserPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        return new PreferencesResponse(
                toWorkPreferenceDto(prefs.workPreference()),
                prefs.salaryFloor(),
                prefs.excludedCompanies());
    }

    /**
     * Converts a domain {@code WorkPreference} variant into its discriminated
     * JSON DTO representation.
     */
    private static WorkPreferenceDto toWorkPreferenceDto(WorkPreference workPreference) {
        if (workPreference == null) {
            return null;
        }
        return switch (workPreference) {
            case WorkPreference.Remote r -> new WorkPreferenceDto.Remote();
            case WorkPreference.Hybrid h -> new WorkPreferenceDto.Hybrid(h.cities());
            case WorkPreference.Onsite o -> new WorkPreferenceDto.Onsite(o.cities());
        };
    }
}
