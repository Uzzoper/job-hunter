package com.juanperuzzo.job_hunter.unit.infrastructure.storage;

import com.juanperuzzo.job_hunter.infrastructure.storage.ResumeStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResumeStorageAdapter tests (issue #92 slice a)")
class ResumeStorageAdapterTest {

    private static final Long USER_ID = 7L;
    private static final byte[] PDF_BYTES = "%PDF-1.4 stored".getBytes();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("write then read should round-trip the PDF bytes at {dir}/{userId}/resume.pdf")
    void write_thenRead_shouldRoundTripBytes() throws Exception {
        var adapter = new ResumeStorageAdapter(tempDir.toString());

        adapter.write(USER_ID, PDF_BYTES);
        var stored = tempDir.resolve(String.valueOf(USER_ID)).resolve("resume.pdf");

        assertTrue(Files.exists(stored), "PDF should live at {uploadDir}/{userId}/resume.pdf");
        assertArrayEquals(PDF_BYTES, adapter.read(USER_ID).orElseThrow());
    }

    @Test
    @DisplayName("read when no file was ever written should return empty")
    void read_whenNoFile_shouldReturnEmptyOptional() {
        var adapter = new ResumeStorageAdapter(tempDir.toString());

        assertEquals(Optional.empty(), adapter.read(99L));
    }
}
