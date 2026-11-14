package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.service.ResumeRetrievalService;
import com.juanperuzzo.job_hunter.domain.exception.ResumeNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeRetrievalService tests (issue #92 slice a)")
class ResumeRetrievalServiceTest {

    private static final Long USER_ID = 71L;
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes();

    @Mock
    private ResumeStorage resumeStorage;

    private ResumeRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new ResumeRetrievalService(resumeStorage);
    }

    @Test
    @DisplayName("downloadResume should return the stored PDF bytes")
    void downloadResume_whenStored_shouldReturnBytes() {
        when(resumeStorage.read(USER_ID)).thenReturn(Optional.of(PDF_BYTES));

        assertArrayEquals(PDF_BYTES, service.downloadResume(USER_ID));
    }

    @Test
    @DisplayName("downloadResume when nothing is stored should throw ResumeNotFoundException")
    void downloadResume_whenMissing_shouldThrowResumeNotFound() {
        when(resumeStorage.read(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResumeNotFoundException.class, () -> service.downloadResume(USER_ID));
    }
}
