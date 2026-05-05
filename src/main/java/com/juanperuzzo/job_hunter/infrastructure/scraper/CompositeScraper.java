package com.juanperuzzo.job_hunter.infrastructure.scraper;

import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class CompositeScraper implements ScraperPort {

    private static final Logger log = LoggerFactory.getLogger(CompositeScraper.class);

    private final List<ScraperPort> scrapers;

    public CompositeScraper(List<ScraperPort> scrapers) {
        this.scrapers = List.copyOf(scrapers);
    }

    @Override
    public List<Job> fetch() {
        var uniqueJobs = new LinkedHashMap<String, Job>();
        var failures = new ArrayList<ScraperException>();
        var successfulScrapers = 0;

        for (var scraper : scrapers) {
            var scraperName = scraper.getClass().getSimpleName();
            var before = uniqueJobs.size();
            try {
                var jobs = scraper.fetch();
                successfulScrapers++;
                jobs.forEach(job -> uniqueJobs.putIfAbsent(job.url(), job));
                log.info("{} returned {} jobs. New unique jobs added: {}", scraperName, jobs.size(),
                        uniqueJobs.size() - before);
            } catch (ScraperException e) {
                failures.add(e);
                log.warn("{} failed and will be skipped: {}", scraperName, e.getMessage());
            }
        }

        if (successfulScrapers == 0 && !failures.isEmpty()) {
            var exception = new ScraperException("All scrapers failed", failures.get(0));
            failures.stream().skip(1).forEach(exception::addSuppressed);
            throw exception;
        }

        log.info("Composite scraper returned {} unique jobs", uniqueJobs.size());
        return List.copyOf(uniqueJobs.values());
    }
}
