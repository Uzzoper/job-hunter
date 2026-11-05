package com.juanperuzzo.job_hunter.unit.infrastructure.persistence;

import com.juanperuzzo.job_hunter.domain.exception.DuplicateUrlException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.persistence.JobEntity;
import com.juanperuzzo.job_hunter.infrastructure.persistence.JobJpaRepository;
import com.juanperuzzo.job_hunter.infrastructure.persistence.JobPersistenceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Adapter contract for the duplicate-insert race
 * ({@code docs/specs/linkedin-posts-ingest.md}, 2026-11-05 round): only the
 * SQLite {@code UNIQUE constraint failed: jobs.url} violation converts into
 * the domain {@link DuplicateUrlException}; every other integrity violation
 * rethrows unchanged so the application layer never inspects Spring DAO
 * types.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobPersistenceAdapter tests")
class JobPersistenceAdapterTest {

    private static final String URL =
            "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678/";

    @Mock
    private JobJpaRepository jpaRepository;

    private JobPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JobPersistenceAdapter(jpaRepository);
    }

    private Job job() {
        return new Job(null, "Dev Ruby", "Acme TI", URL, "text", LocalDate.of(2026, 11, 4),
                "linkedin-post", "rh@acme.com.br", null, true);
    }

    @Test
    @DisplayName("save should map the persisted entity back to the domain record, flag included")
    void save_whenPersistenceSucceeds_shouldMapDomainRoundTripWithSuspectFlag() {
        var persisted = new JobEntity(5L, "Dev Ruby", "Acme TI", URL, "text",
                LocalDate.of(2026, 11, 4), "linkedin-post", "rh@acme.com.br", null, true);
        when(jpaRepository.save(any(JobEntity.class))).thenReturn(persisted);

        var saved = adapter.save(job());

        assertEquals(5L, saved.id());
        assertEquals(URL, saved.url());
        assertEquals("rh@acme.com.br", saved.contactEmail());
        assertEquals(true, saved.emailSuspect(), "flag must survive the entity round trip");
    }

    @Test
    @DisplayName("save should convert only the jobs.url UNIQUE violation into DuplicateUrlException")
    void save_whenUniqueUrlViolation_shouldThrowDuplicateUrlException() {
        doThrow(new DataIntegrityViolationException(
                "could not execute statement; SQL [insert into jobs ...]; "
                        + "UNIQUE constraint failed: jobs.url"))
                .when(jpaRepository).save(any(JobEntity.class));

        var thrown = assertThrows(DuplicateUrlException.class, () -> adapter.save(job()),
                "the application layer must never inspect a Spring DAO type");
        assertInstanceOf(DuplicateUrlException.class, thrown);
    }

    @Test
    @DisplayName("save should rethrow any other integrity violation unchanged")
    void save_whenOtherIntegrityViolation_shouldRethrowUnchanged() {
        var dive = new DataIntegrityViolationException(
                "could not execute statement; SQL [insert into jobs ...]; "
                        + "NOT NULL constraint failed: jobs.title");
        doThrow(dive).when(jpaRepository).save(any(JobEntity.class));

        var thrown = assertThrows(DataIntegrityViolationException.class, () -> adapter.save(job()));
        assertSame(dive, thrown, "non-url integrity violations must surface untouched");
    }
}
