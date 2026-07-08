package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;

import java.util.List;

public interface ExtractionStrategy {
    String providerId();
    List<RawJob> extract();
}
