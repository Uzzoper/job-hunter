package com.juanperuzzo.job_hunter.unit.infrastructure.scraper;

import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.CompositeScraper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("CompositeScraper tests")
class CompositeScraperTest {

    @Test
    @DisplayName("fetch should combine multiple scrapers and deduplicate jobs by URL")
    void fetch_whenMultipleScrapersReturnDuplicateUrls_shouldCombineAndDeduplicate() {
        var gupyJob = new Job(null, "Desenvolvedor Java Júnior", "Company A", "https://example.com/jobs/1",
                "Description", LocalDate.now(), Optional.empty());
        var duplicateInfoJobsJob = new Job(null, "Desenvolvedor Java Júnior", "Company A", "https://example.com/jobs/1",
                "Description from another source", LocalDate.now(), Optional.empty());
        var infoJobsOnlyJob = new Job(null, "Desenvolvedor Backend Júnior", "Company B", "https://example.com/jobs/2",
                "Description", LocalDate.now(), Optional.empty());

        ScraperPort gupyScraper = () -> List.of(gupyJob);
        ScraperPort infoJobsScraper = () -> List.of(duplicateInfoJobsJob, infoJobsOnlyJob);
        var compositeScraper = new CompositeScraper(List.of(gupyScraper, infoJobsScraper));

        var jobs = compositeScraper.fetch();

        assertEquals(2, jobs.size());
        assertEquals(List.of(gupyJob, infoJobsOnlyJob), jobs);
    }

    @Test
    @DisplayName("fetch should continue when one scraper fails and another returns jobs")
    void fetch_whenOneScraperFails_shouldReturnJobsFromOtherScrapers() {
        var infoJobsOnlyJob = new Job(null, "Desenvolvedor Backend Júnior", "Company B", "https://example.com/jobs/2",
                "Description", LocalDate.now(), Optional.empty());
        ScraperPort failingScraper = () -> {
            throw new ScraperException("Gupy unavailable");
        };
        ScraperPort workingScraper = () -> List.of(infoJobsOnlyJob);
        var compositeScraper = new CompositeScraper(List.of(failingScraper, workingScraper));

        var jobs = compositeScraper.fetch();

        assertEquals(List.of(infoJobsOnlyJob), jobs);
    }

    @Test
    @DisplayName("fetch should throw ScraperException when all scrapers fail")
    void fetch_whenAllScrapersFail_shouldThrowScraperException() {
        ScraperPort failingGupyScraper = () -> {
            throw new ScraperException("Gupy unavailable");
        };
        ScraperPort failingInfoJobsScraper = () -> {
            throw new ScraperException("InfoJobs unavailable");
        };
        var compositeScraper = new CompositeScraper(List.of(failingGupyScraper, failingInfoJobsScraper));

        var exception = assertThrows(ScraperException.class, compositeScraper::fetch);

        assertEquals("All scrapers failed", exception.getMessage());
    }
}
