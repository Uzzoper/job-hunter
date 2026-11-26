package com.juanperuzzo.job_hunter.unit.infrastructure.storage;

import com.juanperuzzo.job_hunter.infrastructure.storage.ResumeStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ResumeStorageAdapter tests")
class ResumeStorageAdapterTest {

    @TempDir
    Path tempDir;

    private static final byte[] PDF_BYTES = "%PDF-1.7 test bytes".getBytes();

    @Test
    @DisplayName("write then read should round-trip the PDF bytes at {dir}/resume.pdf")
    void write_thenRead_shouldRoundTripBytes() {
        var adapter = new ResumeStorageAdapter(tempDir.toString());

        adapter.write(PDF_BYTES);
        var stored = tempDir.resolve("resume.pdf");

        assertEquals(true, Files.exists(stored), "PDF should live at {uploadDir}/resume.pdf");
        assertArrayEquals(PDF_BYTES, adapter.read().orElseThrow());
    }

    @Test
    @DisplayName("read when no file was ever written should return empty")
    void read_whenNoFile_shouldReturnEmptyOptional() {
        var adapter = new ResumeStorageAdapter(tempDir.toString());

        assertEquals(Optional.empty(), adapter.read());
    }

    @Test
    @DisplayName("read when only a legacy {dir}/1/resume.pdf exists should return its bytes")
    void read_whenLegacyPathExists_shouldFallback() throws IOException {
        var adapter = new ResumeStorageAdapter(tempDir.toString());
        var legacy = tempDir.resolve("1").resolve("resume.pdf");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, PDF_BYTES);

        assertArrayEquals(PDF_BYTES, adapter.read().orElseThrow());
    }
}
