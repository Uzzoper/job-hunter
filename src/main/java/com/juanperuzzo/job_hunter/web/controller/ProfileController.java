package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.CurrentUserProvider;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.ProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserProfileService userProfileService;
    private final CurrentUserProvider currentUserService;

    public ProfileController(UserProfileService userProfileService, CurrentUserProvider currentUserService) {
        this.userProfileService = userProfileService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile() {
        Long userId = currentUserService.getCurrentUserId();
        var profile = userProfileService.getProfile(userId);
        var response = new ProfileResponse(
                profile.id(), profile.userId(), profile.resumeText(),
                profile.skills(), profile.tone());
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> saveProfile(@Valid @RequestBody ProfileRequest request) {
        Long userId = currentUserService.getCurrentUserId();
        var profile = userProfileService.saveProfile(
                userId, request.resumeText(), request.skills(), request.tone());
        var response = new ProfileResponse(
                profile.id(), profile.userId(), profile.resumeText(),
                profile.skills(), profile.tone());
        return ResponseEntity.ok(response);
    }

}
