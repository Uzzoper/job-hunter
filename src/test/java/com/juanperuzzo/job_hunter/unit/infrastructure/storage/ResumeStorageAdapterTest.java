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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("read should find a unique legacy resume under a non-one user id")
    void read_whenUniqueLegacyPathUsesNonOneUserId_shouldFallback() throws IOException {
        var adapter = new ResumeStorageAdapter(tempDir.toString());
        var legacy = tempDir.resolve("42").resolve("resume.pdf");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, PDF_BYTES);

        assertArrayEquals(PDF_BYTES, adapter.read().orElseThrow());
    }

    @Test
    @DisplayName("read should select the configured owner resume when multiple legacy files exist")
    void read_whenOwnerIdConfigured_shouldSelectThatLegacyResume() throws IOException {
        var ownerBytes = "owner resume".getBytes();
        writeLegacyResume("1", "other resume".getBytes());
        writeLegacyResume("42", ownerBytes);
        var adapter = new ResumeStorageAdapter(tempDir.toString(), "42");

        assertArrayEquals(ownerBytes, adapter.read().orElseThrow());
    }

    @Test
    @DisplayName("read should fail clearly when multiple legacy files make the owner ambiguous")
    void read_whenMultipleLegacyFilesAndNoOwnerConfigured_shouldThrow() throws IOException {
        writeLegacyResume("1", "first resume".getBytes());
        writeLegacyResume("42", "second resume".getBytes());
        var adapter = new ResumeStorageAdapter(tempDir.toString());

        var error = assertThrows(IllegalStateException.class, adapter::read);
        assertTrue(error.getMessage().contains("owner-user-id"));
    }

    private void writeLegacyResume(String userId, byte[] bytes) throws IOException {
        var legacy = tempDir.resolve(userId).resolve("resume.pdf");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, bytes);
    }
}
