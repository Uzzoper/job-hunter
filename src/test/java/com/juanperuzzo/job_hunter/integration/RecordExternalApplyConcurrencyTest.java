package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyResult;
import com.juanperuzzo.job_hunter.application.service.RecordExternalApplyService;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.persistence.EmailDraftJpaRepository;
import com.juanperuzzo.job_hunter.infrastructure.persistence.JobPersistenceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency proof for issue #55: N genuinely simultaneous
 * {@code record()} calls for the same (jobId, userId) must leave exactly one
 * row, succeed for every caller, report {@code created=true} exactly once,
 * and never surface a 409.
 *
 * <p>Boots the full Spring context against a temp-file SQLite datasource
 * (same pattern as {@code SqliteBaselineIntegrationTest}) so the unique
 * constraint and the SQLite write lock behave like production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("RecordExternalApply concurrency integration test")
class RecordExternalApplyConcurrencyTest {

    private static final int CALLERS = 10;

    private static final Path SQLITE_FILE = createTempSqliteFile();

    private static Path createTempSqliteFile() {
        try {
            var file = Files.createTempFile("jobhunter-race-test-", ".db");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary SQLite database file", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_FILE.toAbsolutePath());
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("jwt.secret", () -> "test-secret-key-min-32-chars-long-for-hmac!!123");
        registry.add("OPENROUTER_API_KEY", () -> "sk-test-dummy-key");
        registry.add("HERMES_API_KEY", () -> "test-hermes-key");
    }

    @Autowired
    private RecordExternalApplyService service;

    @Autowired
    private JobPersistenceAdapter jobRepository;

    @Autowired
    private EmailDraftJpaRepository emailDraftJpaRepository;

    @Test
    @DisplayName("ten simultaneous records should leave one row, all succeed, one created, zero 409")
    void record_whenTenCallersRace_shouldLeaveOneRowAndSucceedForAll() throws Exception {
        var job = jobRepository.save(new Job(null, "Developer", "Acme",
                "https://acme.com/job/race-1", "Description", LocalDate.now(), "test"));

        var barrier = new CyclicBarrier(CALLERS);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            List<Future<RecordExternalApplyResult>> futures = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return service.record(1L, job.id());
                }));
            }
            var results = new ArrayList<RecordExternalApplyResult>();
            for (var future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(emailDraftJpaRepository.count()).isEqualTo(1);
            assertThat(results).hasSize(CALLERS);
            assertThat(results.stream().filter(RecordExternalApplyResult::created).count()).isEqualTo(1);
            assertThat(results.stream().map(r -> r.draft().status().name()).distinct().toList())
                    .containsExactly("SENT");
        } finally {
            pool.shutdownNow();
        }
    }
}
