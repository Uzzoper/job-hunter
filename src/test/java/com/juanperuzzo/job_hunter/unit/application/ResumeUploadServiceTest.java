package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
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

    private ResumeUploadService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("resume-upload-test-");
        service = new ResumeUploadService(aiPort, userProfileService, userProfileRepository, tempDir.toString());
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
                List.of(new Project("ProjectX", "A project", "Java, Maven")));

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), anyString(), anyList(), eq(CompanyTone.FORMAL), anyList()))
                .thenReturn(profile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        assertEquals(profile, result);
        verify(aiPort).complete(anyString());
        verify(userProfileService).saveProfile(eq(1L), anyString(),
                eq(List.of("Java", "Spring Boot")), eq(CompanyTone.FORMAL),
                eq(List.of(new Project("ProjectX", "A project", "Java, Maven"))));
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
                List.of("Java"), CompanyTone.FORMAL, List.of());

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), anyString(), anyList(), eq(CompanyTone.FORMAL), anyList()))
                .thenReturn(profile);

        var result = service.uploadResume(1L, file);

        assertNotNull(result);
        verify(userProfileService).saveProfile(eq(1L), anyString(),
                eq(List.of("Java")), eq(CompanyTone.FORMAL), anyList());
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
        when(userProfileService.saveProfile(anyLong(), anyString(), anyList(), any(), anyList()))
                .thenThrow(new UserNotFoundException("User not found with id: 1"));

        assertThrows(UserNotFoundException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should propagate IllegalArgumentException from userProfileService")
    void uploadResume_whenResumeTextTooShort_shouldPropagateIllegalArgument() throws Exception {
        var file = validPdfMock("short"); // text < 50 chars

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(anyLong(), anyString(), anyList(), any(), anyList()))
                .thenThrow(new IllegalArgumentException("Resume text must be at least 50 characters"));

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadResume(1L, file));
    }

    @Test
    @DisplayName("uploadResume should keep existing tone when profile exists")
    void uploadResume_whenExistingProfile_shouldPreserveTone() throws Exception {
        var file = validPdfMock("Existing user with a CASUAL tone in the existing profile record.");
        var existingProfile = new UserProfile(5L, 1L, "Old resume...", List.of("Java"), CompanyTone.CASUAL, List.of());
        var expectedProfile = new UserProfile(5L, 1L, "Existing user with a CASUAL tone in the existing profile record.",
                List.of("Spring"), CompanyTone.CASUAL, List.of());

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [\"Spring\"], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existingProfile));
        when(userProfileService.saveProfile(eq(1L), anyString(), eq(List.of("Spring")), eq(CompanyTone.CASUAL), anyList()))
                .thenReturn(expectedProfile);

        var result = service.uploadResume(1L, file);

        assertEquals(expectedProfile, result);
        verify(userProfileService).saveProfile(eq(1L), anyString(), eq(List.of("Spring")),
                eq(CompanyTone.CASUAL), anyList());
    }

    @Test
    @DisplayName("uploadResume should default to FORMAL tone when no profile exists")
    void uploadResume_whenNoExistingProfile_shouldDefaultToneToFormal() throws Exception {
        var file = validPdfMock("New user uploading resume for the very first time.");
        var expectedProfile = new UserProfile(null, 1L, "New user uploading resume for the very first time.",
                List.of("Kotlin"), CompanyTone.FORMAL, List.of());

        when(aiPort.complete(anyString())).thenReturn("{\"skills\": [\"Kotlin\"], \"projects\": []}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(userProfileService.saveProfile(eq(1L), anyString(), eq(List.of("Kotlin")), eq(CompanyTone.FORMAL), anyList()))
                .thenReturn(expectedProfile);

        var result = service.uploadResume(1L, file);

        assertEquals(expectedProfile, result);
        verify(userProfileService).saveProfile(eq(1L), anyString(), eq(List.of("Kotlin")),
                eq(CompanyTone.FORMAL), anyList());
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
