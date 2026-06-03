package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.infrastructure.security.CurrentUserService;
import com.juanperuzzo.job_hunter.web.controller.ProfileController;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProfileController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, CurrentUserService.class})
@DisplayName("ProfileController tests")
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getProfile should return 200 and profile when profile exists")
    void getProfile_whenProfileExists_shouldReturn200() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(1L, 1L, "Experienced dev...", List.of("Java", "Spring Boot"), CompanyTone.FORMAL);
        when(userProfileService.getProfile(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.resumeText").value("Experienced dev..."))
                .andExpect(jsonPath("$.skills[0]").value("Java"))
                .andExpect(jsonPath("$.tone").value("FORMAL"));
    }

    @Test
    @DisplayName("getProfile should return 400 when profile is not configured")
    void getProfile_whenProfileNotFound_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        when(userProfileService.getProfile(1L))
                .thenThrow(new ProfileNotConfiguredException("Profile not configured for user: 1"));

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveProfile should return 200 and updated profile when request is valid")
    void saveProfile_whenValidRequest_shouldReturn200() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("New resume text", List.of("Java", "Python"), CompanyTone.STARTUP);
        var profile = new UserProfile(1L, 1L, "New resume text", List.of("Java", "Python"), CompanyTone.STARTUP);

        when(userProfileService.saveProfile(1L, "New resume text", List.of("Java", "Python"), CompanyTone.STARTUP))
                .thenReturn(profile);

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeText").value("New resume text"))
                .andExpect(jsonPath("$.skills[0]").value("Java"))
                .andExpect(jsonPath("$.skills[1]").value("Python"))
                .andExpect(jsonPath("$.tone").value("STARTUP"));
    }

    @Test
    @DisplayName("saveProfile should return 400 when service throws exception")
    void saveProfile_whenServiceThrowsException_shouldReturn4xx() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("", List.of(), CompanyTone.FORMAL);

        when(userProfileService.saveProfile(eq(1L), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid input"));

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getProfile should return 200 when userId principal is valid")
    void getProfile_whenUserIdPrincipalIsValid_shouldCallService() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(42L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(2L, 42L, "Dev resume", List.of("Go"), CompanyTone.CASUAL);
        when(userProfileService.getProfile(42L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.resumeText").value("Dev resume"))
                .andExpect(jsonPath("$.skills[0]").value("Go"))
                .andExpect(jsonPath("$.tone").value("CASUAL"));
    }
}
