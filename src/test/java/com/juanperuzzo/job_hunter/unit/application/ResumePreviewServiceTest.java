package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.dto.ResumePreview;
import com.juanperuzzo.job_hunter.application.port.in.AccountIdentity;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumePreviewService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumePreviewService tests (issue #92 slice b)")
class ResumePreviewServiceTest {

    @Mock
    private AiPort aiPort;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserProfileRepository userProfileRepository;

    private ResumePreviewService service;

    @BeforeEach
    void setUp() {
        service = new ResumePreviewService(aiPort, userProfileService, userProfileRepository, 8000);
    }

    @Test
    @DisplayName("preview should return the merged preview and persist nothing")
    void preview_whenValidPdf_shouldReturnPreviewWithoutSaving() throws Exception {
        var file = validPdfMock("Resume text for the stateless preview flow.");
        var aiJson = """
                {"skills": ["Java", "Spring Boot"],
                 "projects": [{"name": "ProjectX", "description": "A project", "techStack": ["Java", "Maven"]}],
                 "contact": {"phone": "ai-phone", "email": "ai@example.com", "portfolioUrl": "https://ai.dev",
                             "githubUrl": "github.com/ai", "linkedinUrl": "linkedin.com/in/ai"}}
                """;
        // stored profile: phone already set (wins over extraction), github filled
        var stored = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 11 90000-0000", null, null, "github.com/stored", null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileService.getAccountIdentity(1L))
                .thenReturn(new AccountIdentity("Test User", "account@example.com"));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));

        var preview = service.preview(1L, file);

        // identity comes from the users row (slice a source of truth)
        assertEquals(new AccountIdentity("Test User", "account@example.com"), preview.accountIdentity());
        // fill-if-empty merge preview: stored phone wins, extracted email/portfolio fill the empties
        assertEquals("+55 11 90000-0000", preview.contact().phone());
        assertEquals("ai@example.com", preview.contact().contactEmail());
        assertEquals("https://ai.dev", preview.contact().portfolioUrl());
        assertEquals(List.of("Java", "Spring Boot"), preview.skills());
        assertEquals(1, preview.projects().size());
        assertEquals(new ResumePreview.ProjectPreview("ProjectX", "A project", List.of("Java", "Maven")),
                preview.projects().get(0));
        // stored tone is what commit would keep
        assertEquals(CompanyTone.CASUAL, preview.tone());
        // digest = md5 of the uploaded bytes (the echo the commit verifies)
        assertEquals(md5Hex(pdfBytes()), preview.digest());
        // nothing is missing after the merge preview
        assertEquals(List.of(), preview.missingFields());
        // resumeTextChars mirrors the extracted text length
        assertEquals(pdfTextLength(pdfBytes()), preview.resumeTextChars());

        // stateless: nothing is written anywhere (the preview service has no
        // ResumeStorage dependency at all — the guarantee is structural)
        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(userProfileService, never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("preview should list contact fields still empty after the merge preview")
    void preview_whenMissingFields_shouldListThemAfterMergePreview() throws Exception {
        var file = validPdfMock("Resume text with partially known contact data.");
        // extraction only knows the email; phone stays on the stored profile; portfolio absent everywhere
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": null, "email": "ai@example.com", "portfolioUrl": null,
                             "githubUrl": null, "linkedinUrl": null}}
                """;
        var stored = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 11 90000-0000", null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileService.getAccountIdentity(1L))
                .thenReturn(new AccountIdentity("Test User", "account@example.com"));
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));

        var preview = service.preview(1L, file);

        assertEquals("+55 11 90000-0000", preview.contact().phone());
        assertEquals("ai@example.com", preview.contact().contactEmail());
        assertNull(preview.contact().portfolioUrl());
        assertEquals(List.of("portfolioUrl"), preview.missingFields(),
                "only the fields still empty after the merge preview are listed");
    }

    @Test
    @DisplayName("preview should throw on an invalid PDF and leave the profile intact")
    void preview_whenInvalidPdf_shouldThrow_andLeaveProfileIntact() {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/msword");

        assertThrows(IllegalArgumentException.class, () -> service.preview(1L, file));

        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(userProfileService, never()).saveProfile(any(), any());
        verify(userProfileService, never()).getAccountIdentity(any());
    }

    @Test
    @DisplayName("preview should surface AiException and leave the profile intact")
    void preview_whenAiFails_shouldSurfaceAiException_andLeaveProfileIntact() throws Exception {
        var file = validPdfMock("Resume text whose AI extraction will fail.");

        when(aiPort.complete(anyString())).thenThrow(new RuntimeException("AI timeout"));

        var ex = assertThrows(AiException.class, () -> service.preview(1L, file));
        assertTrue(ex.getMessage().contains("AI extraction failed"), ex.getMessage());

        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(userProfileService, never()).saveProfile(any(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MultipartFile validPdfMock(String text) throws Exception {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(pdfBytes()));
        when(file.getBytes()).thenReturn(pdfBytes());
        return file;
    }

    private static byte[] pdfBytes() throws IOException {
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(100, 700);
                cs.showText("Resume text for the stateless preview flow.");
                cs.endText();
            }
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Independent re-derivation of the extracted text (same library, test-side). */
    private static String pdfText(byte[] bytes) throws IOException {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pdfTextLength(byte[] bytes) throws IOException {
        return pdfText(bytes).length();
    }

    private static String md5Hex(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
