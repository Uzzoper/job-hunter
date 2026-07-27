package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.CurrentUserProvider;
import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.ProfileResponse;
import com.juanperuzzo.job_hunter.web.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
                .map(p -> new Project(p.name(), p.description(), p.techStack()))
                .toList();
        var profile = userProfileService.saveProfile(
                userId, request.resumeText(), request.skills(), request.tone(), projects);
        var response = toResponse(profile);
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
                .map(p -> new ProjectResponse(p.name(), p.description(), p.techStack()))
                .toList();
        return new ProfileResponse(
                profile.id(), profile.userId(), profile.resumeText(),
                profile.skills(), profile.tone(), projectResponses);
    }
}
