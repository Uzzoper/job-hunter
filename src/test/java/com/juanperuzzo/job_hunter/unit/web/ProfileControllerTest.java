package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import com.juanperuzzo.job_hunter.infrastructure.security.CurrentUserService;
import com.juanperuzzo.job_hunter.web.controller.ProfileController;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.PreferencesRequest;
import com.juanperuzzo.job_hunter.web.dto.PreferencesResponse;
import com.juanperuzzo.job_hunter.web.dto.WorkPreferenceDto;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    private UserProfileUseCase userProfileService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private ResumeUploadService resumeUploadService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getProfile should return 200 and profile when profile exists")
    void getProfile_whenProfileExists_shouldReturn200() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(1L, 1L, "Experienced dev...", List.of("Java", "Spring Boot"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null, null);
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

        var request = new ProfileRequest("New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test", List.of("Java", "Python"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);
        var profile = new UserProfile(1L, 1L, "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test", List.of("Java", "Python"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);

        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(profile);

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeText").value("New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test"))
                .andExpect(jsonPath("$.skills[0]").value("Java"))
                .andExpect(jsonPath("$.skills[1]").value("Python"))
                .andExpect(jsonPath("$.tone").value("STARTUP"));
    }

    @Test
    @DisplayName("saveProfile when null resumeText should return 400")
    void saveProfile_whenNullResumeText_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest(null, List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);

        mockMvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveProfile when short resumeText should return 400")
    void saveProfile_whenShortResumeText_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("short", List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);

        mockMvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveProfile when empty skills should return 400")
    void saveProfile_whenEmptySkills_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("A very long resume text that is certainly more than fifty characters long to pass validation", List.of(), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);

        mockMvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveProfile when null tone should return 400")
    void saveProfile_whenNullTone_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("A very long resume text that is certainly more than fifty characters long to pass validation", List.of("Java"), null, List.of(),
                null, null, null, null, null, null);

        mockMvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveProfile should return 400 when service throws exception")
    void saveProfile_whenServiceThrowsException_shouldReturn4xx() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var request = new ProfileRequest("New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test", List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null, null);

        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
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

        var profile = new UserProfile(2L, 42L, "Dev resume", List.of("Go"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null, null);
        when(userProfileService.getProfile(42L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.resumeText").value("Dev resume"))
                .andExpect(jsonPath("$.skills[0]").value("Go"))
                .andExpect(jsonPath("$.tone").value("CASUAL"));
    }

    @Test
    @DisplayName("saveProfile should accept and echo contact fields")
    void saveProfile_withContactFields_shouldReturn200AndEchoThem() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var resume = "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test";
        var request = new ProfileRequest(resume, List.of("Java"), CompanyTone.FORMAL, List.of(),
                "(42) 99999-0000", "me@example.com", "https://me.dev",
                "https://github.com/me", "https://linkedin.com/in/me", null);
        var profile = new UserProfile(1L, 1L, resume, List.of("Java"), CompanyTone.FORMAL, List.of(),
                "(42) 99999-0000", "me@example.com", "https://me.dev",
                "https://github.com/me", "https://linkedin.com/in/me", null);

        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class))).thenReturn(profile);

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("(42) 99999-0000"))
                .andExpect(jsonPath("$.contactEmail").value("me@example.com"))
                .andExpect(jsonPath("$.portfolioUrl").value("https://me.dev"))
                .andExpect(jsonPath("$.githubUrl").value("https://github.com/me"))
                .andExpect(jsonPath("$.linkedinUrl").value("https://linkedin.com/in/me"));
    }

    @Test
    @DisplayName("getProfile should return stored contact fields")
    void getProfile_withContactFields_shouldReturnThem() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(1L, 1L, "Experienced dev...", List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, "stored@example.com", "https://stored.dev", null, null, null);
        when(userProfileService.getProfile(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactEmail").value("stored@example.com"))
                .andExpect(jsonPath("$.portfolioUrl").value("https://stored.dev"));
    }

    @Test
    @DisplayName("uploadResume should return 200 and profile when file is valid")
    void uploadResume_whenValidPdf_shouldReturn200() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(1L, 1L, "Experienced Java developer with Spring Boot",
                List.of("Java", "Spring Boot"), CompanyTone.FORMAL,
                List.of(new Project("ProjectX", "A project", "Java, Maven")),
                null, null, null, null, null, null);
        var pdfBytes = "fake pdf content".getBytes();
        var multipartFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", pdfBytes);

        when(resumeUploadService.uploadResume(eq(1L), any())).thenReturn(profile);

        mockMvc.perform(multipart("/api/profile/upload-resume")
                        .file(multipartFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.skills[0]").value("Java"))
                .andExpect(jsonPath("$.skills[1]").value("Spring Boot"))
                .andExpect(jsonPath("$.tone").value("FORMAL"));
    }

    @Test
    @DisplayName("uploadResume should return 404 when user is not found")
    void uploadResume_whenUserNotFound_shouldReturn404() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                new User(99L, "ghost@test.com", "Ghost", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var multipartFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        when(resumeUploadService.uploadResume(eq(99L), any()))
                .thenThrow(new UserNotFoundException("User not found with id: 99"));

        mockMvc.perform(multipart("/api/profile/upload-resume")
                        .file(multipartFile))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("uploadResume should return 502 when AI extraction fails")
    void uploadResume_whenAiFails_shouldReturn502() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var multipartFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        when(resumeUploadService.uploadResume(eq(1L), any()))
                .thenThrow(new AiException("AI extraction failed"));

        mockMvc.perform(multipart("/api/profile/upload-resume")
                        .file(multipartFile))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("uploadResume should return 400 when service throws IllegalArgumentException")
    void uploadResume_whenInvalidInput_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var multipartFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        when(resumeUploadService.uploadResume(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("Invalid input"));

        mockMvc.perform(multipart("/api/profile/upload-resume")
                        .file(multipartFile))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getProfile should expose typed workPreference when set on profile")
    void getProfile_withPreferences_shouldExposeThem() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var prefs = new UserPreferences(new WorkPreference.Remote(), 5000, List.of("Acme"));
        var profile = new UserProfile(1L, 1L, "Experienced dev...", List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null, prefs);
        when(userProfileService.getProfile(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.workPreference.type").value("remote"))
                .andExpect(jsonPath("$.preferences.salaryFloor").value(5000))
                .andExpect(jsonPath("$.preferences.excludedCompanies[0]").value("Acme"));
    }

    @Test
    @DisplayName("getProfile should return null preferences when not set")
    void getProfile_withoutPreferences_shouldReturnNull() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var profile = new UserProfile(1L, 1L, "Experienced dev...", List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null, null);
        when(userProfileService.getProfile(1L)).thenReturn(profile);

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("saveProfile without preferences should preserve existing preferences")
    void saveProfile_withoutPreferences_shouldPreserveExisting() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var resume = "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test";
        var request = new ProfileRequest(resume, List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, null);
        var prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), 3000, List.of());
        var profile = new UserProfile(1L, 1L, resume, List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, prefs);

        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(profile);

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.workPreference.type").value("hybrid"))
                .andExpect(jsonPath("$.preferences.workPreference.cities[0]").value("Curitiba"))
                .andExpect(jsonPath("$.preferences.salaryFloor").value(3000));
    }

    @Test
    @DisplayName("saveProfile with preferences should override all preference fields")
    void saveProfile_withPreferences_shouldOverride() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var resume = "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test";
        var requestPrefs = new PreferencesRequest(new WorkPreferenceDto.Hybrid(List.of("São Paulo")), 6000, List.of());
        var request = new ProfileRequest(resume, List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, requestPrefs);
        var prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("São Paulo")), 6000, List.of());
        var profile = new UserProfile(1L, 1L, resume, List.of("Java"), CompanyTone.STARTUP, List.of(),
                null, null, null, null, null, prefs);

        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(profile);

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.workPreference.type").value("hybrid"))
                .andExpect(jsonPath("$.preferences.workPreference.cities[0]").value("São Paulo"))
                .andExpect(jsonPath("$.preferences.salaryFloor").value(6000));
    }

    @Test
    @DisplayName("saveProfile with hybrid workPreference missing cities should return 400")
    void saveProfile_whenHybridWithoutCities_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String payload = """
                {
                  "resumeText": "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test",
                  "skills": ["Java"],
                  "tone": "STARTUP",
                  "projects": [],
                  "preferences": {
                    "workPreference": { "type": "hybrid" },
                    "salaryFloor": 4000,
                    "excludedCompanies": []
                  }
                }
                """;

        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());

        // Rejected before reaching the service (bean validation tier)
        verify(userProfileService, never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("saveProfile with unknown workPreference type should return 400 (deserialization tier)")
    void saveProfile_whenUnknownWorkPreferenceType_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String payload = """
                {
                  "resumeText": "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test",
                  "skills": ["Java"],
                  "tone": "STARTUP",
                  "projects": [],
                  "preferences": {
                    "workPreference": { "type": "hybridity", "cities": ["Curitiba"] }
                  }
                }
                """;

        // Jackson cannot resolve the @JsonTypeInfo subtype "hybridity" →
        // HttpMessageNotReadableException → 400 by GlobalExceptionHandler.
        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("saveProfile with hybrid empty cities list should return 400 (bean validation tier)")
    void saveProfile_whenHybridEmptyCities_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String payload = """
                {
                  "resumeText": "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test",
                  "skills": ["Java"],
                  "tone": "STARTUP",
                  "projects": [],
                  "preferences": {
                    "workPreference": { "type": "hybrid", "cities": [] }
                  }
                }
                """;

        // @NotEmpty on WorkPreferenceDto.Hybrid.cities → MethodArgumentNotValidException → 400.
        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("saveProfile with blank-string city should return 400 (domain constructor tier)")
    void saveProfile_whenOnsiteBlankStringCities_shouldReturn400() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(new User(1L, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String payload = """
                {
                  "resumeText": "New resume text that is certainly long enough to pass the minimum length constraint of fifty characters for test",
                  "skills": ["Java"],
                  "tone": "STARTUP",
                  "projects": [],
                  "preferences": {
                    "workPreference": { "type": "onsite", "cities": ["   "] }
                  }
                }
                """;

        // Bean validation passes (list has 1 element, blank short string) — but the
        // domain compact constructor trims and rejects the all-blank city list →
        // IllegalArgumentException → 400 by GlobalExceptionHandler. This pins the
        // two-tier contract: bean validation + handler both return 400.
        mockMvc.perform(put("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).saveProfile(any(), any());
    }
}
