package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public interface JobRepository {

    boolean existsByUrl(String url);

    Job save(Job job);

    List<Job> findAll();
}
