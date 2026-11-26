package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.dto.ResumeCorrections;
import com.juanperuzzo.job_hunter.application.dto.ResumePreview;
import com.juanperuzzo.job_hunter.application.port.in.ResumeRetrievalUseCase;
import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.service.ResumeCommitService;
import com.juanperuzzo.job_hunter.application.service.ResumePreviewService;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.ProfileResponse;
import com.juanperuzzo.job_hunter.web.dto.PreferencesResponse;
import com.juanperuzzo.job_hunter.web.dto.ProjectResponse;
import com.juanperuzzo.job_hunter.web.dto.ResumeCorrectionsRequest;
import com.juanperuzzo.job_hunter.web.dto.ResumePreviewResponse;
import com.juanperuzzo.job_hunter.web.dto.WorkPreferenceDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserProfileUseCase userProfileService;
    private final ResumeUploadService resumeUploadService;
    private final ResumeRetrievalUseCase resumeRetrievalUseCase;
    private final ResumePreviewService resumePreviewService;
    private final ResumeCommitService resumeCommitService;

    public ProfileController(UserProfileUseCase userProfileService,
                             ResumeUploadService resumeUploadService,
                             ResumeRetrievalUseCase resumeRetrievalUseCase,
                             ResumePreviewService resumePreviewService,
                             ResumeCommitService resumeCommitService) {
        this.userProfileService = userProfileService;
        this.resumeUploadService = resumeUploadService;
        this.resumeRetrievalUseCase = resumeRetrievalUseCase;
        this.resumePreviewService = resumePreviewService;
        this.resumeCommitService = resumeCommitService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile() {
        var profile = userProfileService.getProfile();
        var response = toResponse(profile);
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> saveProfile(@Valid @RequestBody ProfileRequest request) {
        boolean hasName = hasText(request.name());
        boolean hasEmail = hasText(request.email());

        List<Project> projects = request.projects().stream()
                .map(p -> new Project(p.name(), p.description(), String.join(", ", p.techStack())))
                .toList();
        var prefs = toUserPreferences(request.preferences());

        // name/email corrections update the profile identity fields directly
        var existing = userProfileService.getProfile();
        String name = hasName ? request.name().trim() : existing.name();
        String email = hasEmail ? request.email().trim() : existing.email();

        var profile = new UserProfile(existing.id(), name, email, request.resumeText(), request.skills(),
                request.tone(), projects, request.phone(), request.contactEmail(),
                request.portfolioUrl(), request.githubUrl(), request.linkedinUrl(),
                prefs);
        var saved = userProfileService.saveProfile(profile);
        var response = toResponse(saved);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload-resume")
    public ResponseEntity<ProfileResponse> uploadResume(@RequestParam("file") MultipartFile file) {
        var profile = resumeUploadService.uploadResume(file);
        var response = toResponse(profile);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/preview-resume")
    public ResponseEntity<ResumePreviewResponse> previewResume(@RequestParam("file") MultipartFile file) {
        var preview = resumePreviewService.preview(file);
        return ResponseEntity.ok(toPreviewResponse(preview));
    }

    @PostMapping("/commit-resume")
    public ResponseEntity<ProfileResponse> commitResume(@RequestParam("file") MultipartFile file,
                                                        @RequestPart(value = "corrections", required = false) @Valid ResumeCorrectionsRequest corrections) {
        var appCorrections = corrections == null ? null : new ResumeCorrections(corrections.name(), corrections.email(), corrections.phone(), corrections.contactEmail(), corrections.portfolioUrl(), corrections.digest());
        var profile = resumeCommitService.commit(file, appCorrections);
        var response = toResponse(profile);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resume")
    public ResponseEntity<byte[]> downloadResume() {
        var pdf = resumeRetrievalUseCase.downloadResume();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.id(), profile.name(), profile.email(),
                profile.resumeText(), profile.skills(), profile.tone(), toProjectResponses(profile.projects()),
                profile.phone(), profile.contactEmail(), profile.portfolioUrl(), profile.githubUrl(),
                profile.linkedinUrl(), toPreferencesResponse(profile.preferences()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private PreferencesResponse toPreferencesResponse(UserPreferences prefs) {
        if (prefs == null) return null;
        return new PreferencesResponse(
                toWorkPreferenceDto(prefs.workPreference()),
                prefs.salaryFloor(),
                prefs.excludedCompanies(),
                prefs.keywords());
    }

    private UserPreferences toUserPreferences(com.juanperuzzo.job_hunter.web.dto.PreferencesRequest req) {
        if (req == null) return null;
        return new UserPreferences(
                req.workPreference() != null ? req.workPreference().toDomain() : null,
                req.salaryFloor(),
                req.excludedCompanies() != null ? List.copyOf(req.excludedCompanies()) : List.of(),
                req.keywords() != null ? List.copyOf(req.keywords()) : List.of());
    }

    private static WorkPreferenceDto toWorkPreferenceDto(com.juanperuzzo.job_hunter.domain.model.WorkPreference workPreference) {
        if (workPreference == null) {
            return null;
        }
        return switch (workPreference) {
            case com.juanperuzzo.job_hunter.domain.model.WorkPreference.Remote r -> new WorkPreferenceDto.Remote();
            case com.juanperuzzo.job_hunter.domain.model.WorkPreference.Hybrid h -> new WorkPreferenceDto.Hybrid(h.cities());
            case com.juanperuzzo.job_hunter.domain.model.WorkPreference.Onsite o -> new WorkPreferenceDto.Onsite(o.cities());
        };
    }

    private List<ProjectResponse> toProjectResponses(List<Project> projects) {
        return projects == null ? List.of() : projects.stream()
                .map(p -> new ProjectResponse(p.name(), p.description(), List.of(p.techStack().split(", "))))
                .toList();
    }

    private ResumePreviewResponse toPreviewResponse(ResumePreview preview) {
        var identity = new ResumePreviewResponse.IdentityResponse(preview.accountIdentity().name(), preview.accountIdentity().email());
        var contact = new ResumePreviewResponse.ContactResponse(preview.contact().phone(), preview.contact().contactEmail(),
                preview.contact().portfolioUrl());
        var projects = preview.projects().stream()
                .map(pr -> new ResumePreviewResponse.ProjectPreviewResponse(pr.name(), pr.description(), pr.techStack()))
                .toList();
        return new ResumePreviewResponse(identity, contact, preview.skills(), projects,
                preview.tone(), preview.resumeTextChars(), preview.digest(), preview.missingFields());
    }
}
