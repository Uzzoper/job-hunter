package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public interface ScraperPort {
    List<Job> fetch();
}
