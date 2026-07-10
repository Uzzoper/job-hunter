package com.juanperuzzo.job_hunter.unit.application.port.out;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RawJob tests")
class RawJobTest {

    @Test
    @DisplayName("create with all fields populated should return matching values")
    void create_withAllFields_shouldReturnMatchingValues() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        RawJob job = new RawJob(
                "Desenvolvedor Java Júnior",
                "CompanyX",
                "https://example.com/job/123",
                "Job description here",
                "2026-07-08",
                "São Paulo - SP",
                "Remoto",
                metadata
        );

        assertEquals("Desenvolvedor Java Júnior", job.title());
        assertEquals("CompanyX", job.company());
        assertEquals("https://example.com/job/123", job.url());
        assertEquals("Job description here", job.description());
        assertEquals("2026-07-08", job.rawDate());
        assertEquals("São Paulo - SP", job.location());
        assertEquals("Remoto", job.workModel());
        assertEquals("value1", job.metadata().get("key1"));
    }

    @Test
    @DisplayName("create with null url should throw NullPointerException")
    void create_withNullUrl_shouldThrowNPE() {
        assertThrows(NullPointerException.class, () -> new RawJob(
                "Title", "Company", null, null, null, null, null, null
        ));
    }

    @Test
    @DisplayName("create with null metadata should default to empty map")
    void create_withNullMetadata_shouldDefaultToEmptyMap() {
        RawJob job = new RawJob(
                "Title", "Company", "https://example.com/job/123",
                null, null, null, null, null
        );

        assertNotNull(job.metadata());
        assertTrue(job.metadata().isEmpty());
    }
}
