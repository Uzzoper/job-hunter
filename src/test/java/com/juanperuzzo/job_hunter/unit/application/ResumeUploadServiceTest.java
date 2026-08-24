package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeUploadService tests")
class ResumeUploadServiceTest {

    @Mock
    private AiPort aiPort;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Captor
    private ArgumentCaptor<List<String>> skillsCaptor;

    @Captor
    private ArgumentCaptor<List<Project>> projectsCaptor;

    @Captor
    private ArgumentCaptor<UserProfile> profileCaptor;

    private ResumeUploadService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("resume-upload-test-");
        service = new ResumeUploadService(aiPort, userProfileService, userProfileRepository, tempDir.toString(), 8000);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var files = Files.walk(tempDir)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    @DisplayName("uploadResume should extract text, call AI, save profile and return it")
    void uploadResume_whenValidPdf_shouldExtractAndSaveProfile() throws Exception {
        var file = validPdfMock("Experienced Java developer with Spring Boot");
        var aiJson = """
                {"skills": ["Java", "Spring Boot"], "projects": [{"name": "ProjectX", "description": "A project", "techStack": ["Java", "Maven"]}]}
                """;
        var profile = new UserProfile(1L, 1L, "Experienced Java developer with Spring Boot",
                List.of("Java", "Spring Boot"), CompanyTone.FORMAL,
                List.of(new Project("ProjectX", "A project", "Java, Maven")),
                null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(profile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        assertEquals(profile, result);
        verify(aiPort).complete(anyString());
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var savedProfile = profileCaptor.getValue();
        assertEquals(List.of("Java", "Spring Boot"), savedProfile.skills());
        assertEquals(CompanyTone.FORMAL, savedProfile.tone());
        assertEquals(List.of(new Project("ProjectX", "A project", "Java, Maven")), savedProfile.projects());
    }

    @Test
    @DisplayName("uploadResume should handle AI response with markdown code fences")
    void uploadResume_whenAiResponseHasMarkdownFences_shouldStripAndParse() throws Exception {
        var file = validPdfMock("Java developer");
        var aiJson = """
                ```json
                {"skills": ["Java"], "projects": []}
                ```
                """;
        var profile = new UserProfile(1L, 1L, "Java developer",
                List.of("Java"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(profile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        assertEquals(List.of("Java"), profileCaptor.getValue().skills());
    }

    @Test
    @DisplayName("uploadResume should throw IllegalArgumentException when file is null")
    void uploadResume_whenNullFile_shouldThrowIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, null));
    }

    @Test
    @DisplayName("uploadResume should throw IllegalArgumentException when file is empty")
    void uploadResume_whenEmptyFile_shouldThrowIllegalArgument() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should throw IllegalArgumentException when content type is not PDF")
    void uploadResume_whenNonPdfContentType_shouldThrowIllegalArgument() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/msword");

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should throw IllegalArgumentException when file extension is not pdf")
    void uploadResume_whenNonPdfExtension_shouldThrowIllegalArgument() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.docx");

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should throw IllegalArgumentException when PDF contains no text")
    void uploadResume_whenPdfHasNoText_shouldThrowIllegalArgument() throws Exception {
        var file = mock(MultipartFile.class);
        var emptyPdf = createPdfBytes("");
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(emptyPdf));

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should throw AiException when AI call fails")
    void uploadResume_whenAiCallFails_shouldThrowAiException() throws Exception {
        var file = validPdfMock("Java developer");

        when(aiPort.complete(anyString())).thenThrow(new RuntimeException("AI timeout"));

        var ex = assertThrows(AiException.class,
                () -> service.uploadResume(1L, file));
        assertTrue(ex.getMessage().contains("AI extraction failed"));
    }

    @Test
    @DisplayName("uploadResume should throw AiException when AI returns invalid JSON")
    void uploadResume_whenAiReturnsInvalidJson_shouldThrowAiException() throws Exception {
        var file = validPdfMock("Java developer");

        when(aiPort.complete(anyString())).thenReturn("not json at all");

        var ex = assertThrows(AiException.class,
                () -> service.uploadResume(1L, file));
        assertTrue(ex.getMessage().contains("AI response contains no valid JSON"));
    }

    @Test
    @DisplayName("uploadResume should throw AiException when AI returns unparseable JSON")
    void uploadResume_whenAiReturnsMalformedJson_shouldThrowAiException() throws Exception {
        var file = validPdfMock("Java developer");

        when(aiPort.complete(anyString())).thenReturn("{skills: broken json");

        var ex = assertThrows(AiException.class,
                () -> service.uploadResume(1L, file));
        assertTrue(ex.getMessage().contains("AI response contains no valid JSON"));
    }

    @Test
    @DisplayName("uploadResume should propagate UserNotFoundException from userProfileService")
    void uploadResume_whenUserNotFound_shouldPropagateUserNotFoundException() throws Exception {
        var file = validPdfMock("Java developer");

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(anyLong(), any(UserProfile.class)))
                .thenThrow(new UserNotFoundException("User not found with id: 1"));

        assertThrows(UserNotFoundException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should propagate InvalidResumeTextException from userProfileService")
    void uploadResume_whenResumeTextTooShort_shouldPropagateInvalidResumeTextException() throws Exception {
        var file = validPdfMock("short"); // text < 50 chars

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(anyLong(), any(UserProfile.class)))
                .thenThrow(new InvalidResumeTextException("Resume text must be at least 50 characters"));

        assertThrows(InvalidResumeTextException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should keep existing tone when profile exists")
    void uploadResume_whenExistingProfile_shouldPreserveTone() throws Exception {
        var file = validPdfMock("Existing user with a CASUAL tone in the existing profile record.");
        var existingProfile = new UserProfile(5L, 1L, "Old resume...", List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null);
        var expectedProfile = new UserProfile(5L, 1L, "Existing user with a CASUAL tone in the existing profile record.",
                List.of("Spring"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [\"Spring\"], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(expectedProfile);

        var result = service.uploadResume(1L, file);

        assertEquals(expectedProfile, result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        assertEquals(List.of("Spring"), profileCaptor.getValue().skills());
        assertEquals(CompanyTone.CASUAL, profileCaptor.getValue().tone());
    }

    @Test
    @DisplayName("uploadResume should default to FORMAL tone when no profile exists")
    void uploadResume_whenNoExistingProfile_shouldDefaultToneToFormal() throws Exception {
        var file = validPdfMock("New user uploading resume for the very first time.");
        var expectedProfile = new UserProfile(null, 1L, "New user uploading resume for the very first time.",
                List.of("Kotlin"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [\"Kotlin\"], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(expectedProfile);

        var result = service.uploadResume(1L, file);

        assertEquals(expectedProfile, result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        assertEquals(List.of("Kotlin"), profileCaptor.getValue().skills());
        assertEquals(CompanyTone.FORMAL, profileCaptor.getValue().tone());
    }

    // =========================================================================
    // Profile auto-fill from resume upload
    // (docs/specs/profile-autofill-from-resume.md — Step 1 RED)
    // =========================================================================

    @Test
    @DisplayName("uploadResume should fill empty contact fields from the AI extraction")
    void upload_whenProfileHasEmptyContacts_shouldFillFromExtraction() throws Exception {
        var file = validPdfMock("John Doe phone +55 42 99999-0000 github.com/juan");
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "+55 42 99999-0000", "email": "juan@example.com",
                             "portfolioUrl": "https://juan.dev", "githubUrl": "github.com/juan",
                             "linkedinUrl": "linkedin.com/in/juan"}}
                """;
        var existingProfile = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null);
        var savedProfile = new UserProfile(5L, 1L, "John Doe phone +55 42 99999-0000 github.com/juan",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99999-0000", "juan@example.com", "https://juan.dev", "github.com/juan",
                "linkedin.com/in/juan");

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(savedProfile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var saved = profileCaptor.getValue();
        assertEquals("+55 42 99999-0000", saved.phone());
        assertEquals("juan@example.com", saved.contactEmail());
        assertEquals("https://juan.dev", saved.portfolioUrl());
        assertEquals("github.com/juan", saved.githubUrl());
        assertEquals("linkedin.com/in/juan", saved.linkedinUrl());
    }

    @Test
    @DisplayName("uploadResume should keep manually-set contacts and fill only empty ones")
    void upload_whenContactAlreadySet_shouldKeepExistingValue() throws Exception {
        var file = validPdfMock("Juan Peruzzo resume with contact details");
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "+55 42 98888-1111", "email": "ai@example.com",
                             "portfolioUrl": "https://ai.dev", "githubUrl": "github.com/ai-extracted",
                             "linkedinUrl": "linkedin.com/in/ai-extracted"}}
                """;
        var existingProfile = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, "github.com/juan", null);
        var savedProfile = new UserProfile(5L, 1L, "Juan Peruzzo resume with contact details",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 98888-1111", "ai@example.com", "https://ai.dev", "github.com/juan",
                "linkedin.com/in/ai-extracted");

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(savedProfile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var saved = profileCaptor.getValue();
        // Manual edit always wins over AI (fill-if-empty rule)
        assertEquals("github.com/juan", saved.githubUrl());
        // Empty contact fields ARE filled from the extraction
        assertEquals("+55 42 98888-1111", saved.phone());
        assertEquals("ai@example.com", saved.contactEmail());
        assertEquals("https://ai.dev", saved.portfolioUrl());
        assertEquals("linkedin.com/in/ai-extracted", saved.linkedinUrl());
    }

    @Test
    @DisplayName("uploadResume should keep contacts unchanged when extraction has no contact object")
    void upload_whenExtractionHasNoContact_shouldKeepContactsAsIs() throws Exception {
        var file = validPdfMock("Resume without any contact information at all");
        var aiJson = """
                {"skills": ["Java"], "projects": []}
                """;
        var existingProfile = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99777-2222", null, null, "github.com/juan", null);
        var savedProfile = new UserProfile(5L, 1L, "Resume without any contact information at all",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99777-2222", null, null, "github.com/juan", null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(savedProfile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var saved = profileCaptor.getValue();
        assertEquals("+55 42 99777-2222", saved.phone());
        assertNull(saved.contactEmail());
        assertNull(saved.portfolioUrl());
        assertEquals("github.com/juan", saved.githubUrl());
        assertNull(saved.linkedinUrl());
    }

    @Test
    @DisplayName("uploadResume should drop only the invalid extracted email and still succeed")
    void upload_whenExtractedEmailInvalid_shouldDropOnlyThatField() throws Exception {
        var file = validPdfMock("Resume with a typo in the extracted contact email");
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "+55 42 99666-3333", "email": "not-an-email",
                             "portfolioUrl": null, "githubUrl": null, "linkedinUrl": null}}
                """;
        var existingProfile = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null);
        var savedProfile = new UserProfile(5L, 1L, "Resume with a typo in the extracted contact email",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99666-3333", null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(savedProfile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var saved = profileCaptor.getValue();
        // Valid field is applied, invalid one is dropped, upload still succeeds
        assertEquals("+55 42 99666-3333", saved.phone());
        assertNull(saved.contactEmail());
    }

    @Test
    @DisplayName("uploadResume should treat a malformed contact node as no contact data")
    void upload_whenContactNodeMalformed_shouldTreatAsNoContactData() throws Exception {
        var file = validPdfMock("Resume uploaded with a malformed AI contact response");
        var aiJson = """
                {"skills": ["Java"], "projects": [], "contact": "some string"}
                """;
        var existingProfile = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99555-4444", null, null, null, null);
        var savedProfile = new UserProfile(5L, 1L, "Resume uploaded with a malformed AI contact response",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 42 99555-4444", null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(savedProfile);

        assertDoesNotThrow(() -> service.uploadResume(1L, file));

        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var saved = profileCaptor.getValue();
        assertEquals("+55 42 99555-4444", saved.phone());
        assertNull(saved.contactEmail());
        assertNull(saved.githubUrl());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MultipartFile validPdfMock(String text) throws Exception {
        var file = mock(MultipartFile.class);
        var pdfBytes = createPdfBytes(text);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(pdfBytes));
        return file;
    }

    private static byte[] createPdfBytes(String text) throws IOException {
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(100, 700);
                cs.showText(text);
                cs.endText();
            }
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
