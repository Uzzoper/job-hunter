package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.dto.ResumeCorrections;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeCommitService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.exception.ResumeDigestMismatchException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeCommitService tests (issue #92 slice b)")
class ResumeCommitServiceTest {

    @Mock
    private AiPort aiPort;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ResumeStorage resumeStorage;

    @Captor
    private ArgumentCaptor<UserProfile> profileCaptor;

    private ResumeCommitService service;

    @BeforeEach
    void setUp() {
        service = new ResumeCommitService(aiPort, userProfileService, userProfileRepository,
                resumeStorage, 8000);
    }

    @Test
    @DisplayName("commit without corrections should apply the fill-if-empty merge and store the resume")
    void commit_whenNoCorrections_shouldApplyFillIfEmptyMerge_andStoreResume() throws Exception {
        var file = validPdfMock("Commit flow resume with enough text to pass validation gates.");
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "ai-phone", "email": "ai@example.com", "portfolioUrl": "https://ai.dev",
                             "githubUrl": "github.com/ai-extracted", "linkedinUrl": null}}
                """;
        // stored profile: githubUrl already set (wins over extraction), contacts empty
        var stored = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, "github.com/stored", null, null);
        var saved = new UserProfile(5L, 1L, "Commit flow resume with enough text to pass validation gates.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "ai-phone", "ai@example.com", "https://ai.dev", "github.com/stored", null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class))).thenReturn(saved);

        var result = service.commit(1L, file, null);

        assertEquals(saved, result);
        // the original PDF is stored at the ResumeStorage port
        verify(resumeStorage).write(eq(1L), any());
        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var merged = profileCaptor.getValue();
        assertEquals("ai-phone", merged.phone());
        assertEquals("ai@example.com", merged.contactEmail());
        assertEquals("https://ai.dev", merged.portfolioUrl());
        // fill-if-empty: stored github wins over extraction
        assertEquals("github.com/stored", merged.githubUrl());
        assertEquals(CompanyTone.CASUAL, merged.tone());
        // no identity corrections → the users row is untouched
        verify(userProfileService, never()).updateIdentity(anyLong(), any(), any());
    }

    @Test
    @DisplayName("commit with chat corrections should override stored values")
    void commit_whenChatCorrection_shouldOverrideStoredValue() throws Exception {
        var file = validPdfMock("Commit flow resume corrected by chat instructions.");
        var aiJson = """
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "ai-phone", "email": "ai@example.com", "portfolioUrl": null,
                             "githubUrl": null, "linkedinUrl": null}}
                """;
        // stored values would win over the extraction — but the chat correction wins over both
        var stored = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 11 97777-0000", "stored@example.com", null, null, null, null);
        var saved = new UserProfile(5L, 1L, "Commit flow resume corrected by chat instructions.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                "+55 11 96666-0000", "corrected@example.com", null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class))).thenReturn(saved);

        var corrections = new ResumeCorrections(null, null,
                "+55 11 96666-0000", "corrected@example.com", null, null);
        service.commit(1L, file, corrections);

        verify(userProfileService).saveProfile(eq(1L), profileCaptor.capture());
        var merged = profileCaptor.getValue();
        // chat correction > stored value > AI extraction
        assertEquals("+55 11 96666-0000", merged.phone());
        assertEquals("corrected@example.com", merged.contactEmail());
    }

    @Test
    @DisplayName("commit with a digest mismatch should throw conflict and leave everything intact")
    void commit_whenDigestMismatch_shouldThrowConflict_andLeaveProfileIntact() throws Exception {
        var file = validPdfMock("Resume text for the digest guard scenario of slice b.");

        // digest echo does not match the uploaded bytes → the confirmed artifact
        // is NOT the previewed one → nothing may be written
        var corrections = new ResumeCorrections(null, null, null, null, null, "deadbeefdeadbeef");

        var ex = assertThrows(ResumeDigestMismatchException.class,
                () -> service.commit(1L, file, corrections));
        assertTrue(ex.getMessage().contains("digest"), ex.getMessage());

        verify(resumeStorage, never()).write(anyLong(), any());
        verify(userProfileService, never()).saveProfile(anyLong(), any());
        verify(userProfileService, never()).updateIdentity(anyLong(), any(), any());
    }

    @Test
    @DisplayName("commit with identity corrections should update the users row")
    void commit_whenIdentityCorrected_shouldUpdateUsersRow() throws Exception {
        var file = validPdfMock("Commit flow resume with account identity corrections.");
        var aiJson = """
                {"skills": ["Java"], "projects": [], "contact": null}
                """;
        var stored = new UserProfile(5L, 1L, "Old resume text that is long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null, null);
        var saved = new UserProfile(5L, 1L, "Commit flow resume with account identity corrections.",
                List.of("Java"), CompanyTone.CASUAL, List.of(),
                null, null, null, null, null, null);

        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(stored));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class))).thenReturn(saved);

        var corrections = new ResumeCorrections("Corrected Name", "corrected@example.com",
                null, null, null, null);
        service.commit(1L, file, corrections);

        // identity write route: the users row is updated through the service port
        verify(userProfileService).updateIdentity(1L, "Corrected Name", "corrected@example.com");
        verify(userProfileService).saveProfile(eq(1L), any(UserProfile.class));
        verify(resumeStorage).write(eq(1L), any());
    }

    @Test
    @DisplayName("commit with an invalid correction should throw validation and write nothing")
    void commit_whenInvalidCorrection_shouldThrowValidation() throws Exception {
        var file = validPdfMock("Resume text for the invalid chat correction scenario.");

        // contactEmail correction fails the existing email format rule
        var corrections = new ResumeCorrections(null, null, null, "not-an-email", null, null);

        assertThrows(IllegalArgumentException.class, () -> service.commit(1L, file, corrections));

        verify(resumeStorage, never()).write(anyLong(), any());
        verify(userProfileService, never()).saveProfile(anyLong(), any());
        verify(userProfileService, never()).updateIdentity(anyLong(), any(), any());
    }

    // =========================================================================
    // Fail-fast gates (review 2026-11-15)
    // =========================================================================

    @Test
    @DisplayName("commit with an invalid identity correction should throw before any write")
    void commit_whenIdentityCorrectionInvalid_shouldThrow_andWriteNothing() throws Exception {
        var file = validPdfMock("Commit flow resume test with an invalid identity correction.");

        // a name correction over the users-row cap (255) must be rejected in the
        // pre-validation pass — not after the PDF is stored and the profile
        // replaced (review 2026-11-15: partial-write finding)
        var corrections = new ResumeCorrections("x".repeat(256), null, null, null, null, null);

        // unused once the gate exists — lenient (same approach as the digest-mismatch test)
        lenient().when(aiPort.complete(anyString())).thenReturn(
                "{\"skills\": [\"Java\"], \"projects\": [], \"contact\": null}");
        lenient().when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(new UserProfile(5L, 1L,
                "Old resume text that is long enough to pass.", List.of("Java"), CompanyTone.CASUAL,
                List.of(), null, null, null, null, null, null)));
        lenient().when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(new UserProfile(5L, 1L, "Commit flow resume test with an invalid identity correction.",
                        List.of("Java"), CompanyTone.CASUAL, List.of(), null, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> service.commit(1L, file, corrections));

        verify(resumeStorage, never()).write(anyLong(), any());
        verify(userProfileService, never()).saveProfile(anyLong(), any());
        verify(userProfileService, never()).updateIdentity(anyLong(), any(), any());
    }

    @Test
    @DisplayName("commit should accept a digest echo in any case")
    void commit_whenUppercaseDigestEcho_shouldStillMatch() throws Exception {
        var bytes = createPdfBytes("Commit flow resume test with an uppercase digest echo.");
        var file = pdfMock(bytes);

        when(aiPort.complete(anyString())).thenReturn(
                "{\"skills\": [\"Java\"], \"projects\": [], \"contact\": null}");
        when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(new UserProfile(5L, 1L,
                "Old resume text that is long enough to pass.", List.of("Java"), CompanyTone.CASUAL,
                List.of(), null, null, null, null, null, null)));
        when(userProfileService.saveProfile(eq(1L), any(UserProfile.class))).thenReturn(new UserProfile(5L, 1L,
                "Commit flow resume test with an uppercase digest echo.", List.of("Java"), CompanyTone.CASUAL,
                List.of(), null, null, null, null, null, null));

        // uppercase echo of the real digest must not false-409 (review 2026-11-15)
        var corrections = new ResumeCorrections(null, null, null, null, null, md5Hex(bytes).toUpperCase());

        var result = service.commit(1L, file, corrections);

        assertEquals(5L, result.id());
        verify(resumeStorage).write(eq(1L), any());
    }

    @Test
    @DisplayName("commit with a too-short resume should throw before storing the PDF")
    void commit_whenResumeTextTooShort_shouldThrowInvalidResumeText_andNotStorePdf() throws Exception {
        var bytes = createPdfBytes("Too short.");
        var file = pdfMock(bytes);

        // the length gate must fire before storage: a rejected resume must not
        // silently replace the stored PDF (review 2026-11-15: validity-domain
        // divergence — preview accepted what commit rejects)
        lenient().when(aiPort.complete(anyString())).thenReturn(
                "{\"skills\": [\"Java\"], \"projects\": [], \"contact\": null}");
        lenient().when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(new UserProfile(5L, 1L,
                "Old resume text that is long enough to pass.", List.of("Java"), CompanyTone.CASUAL,
                List.of(), null, null, null, null, null, null)));
        lenient().when(userProfileService.saveProfile(eq(1L), any(UserProfile.class)))
                .thenReturn(new UserProfile(5L, 1L, "Too short.", List.of("Java"), CompanyTone.CASUAL,
                        List.of(), null, null, null, null, null, null));

        assertThrows(InvalidResumeTextException.class, () -> service.commit(1L, file, null));

        verify(resumeStorage, never()).write(anyLong(), any());
        verify(userProfileService, never()).updateIdentity(anyLong(), any(), any());
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
        // Lenient: aborting flows (digest mismatch, invalid correction) never
        // consume every stub — same approach as ResumeUploadServiceTest.
        lenient().when(file.getInputStream()).thenReturn(new ByteArrayInputStream(pdfBytes));
        when(file.getBytes()).thenReturn(pdfBytes);
        return file;
    }

    /** Mock over a caller-owned byte instance — the digest tests need the SAME bytes. */
    private MultipartFile pdfMock(byte[] bytes) throws IOException {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        lenient().when(file.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        when(file.getBytes()).thenReturn(bytes);
        return file;
    }

    /** Test-side md5, independent from the pipeline helper under test. */
    private static String md5Hex(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(bytes));
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
