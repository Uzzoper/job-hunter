package com.juanperuzzo.job_hunter.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import static java.util.Objects.requireNonNull;
import java.util.Optional;

public record Job(
        Long id,
        String title,
        String company,
        String url,
        String description,
        LocalDate postedAt,
        Optional<Integer> matchScore
) {
    private static final int EXPIRATION_DAYS = 30;

    public Job {
        requireNonNull(url, "url must not be null");
        requireNonNull(postedAt, "postedAt must not be null");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Job job)) return false;
        return url.equals(job.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    public boolean isExpired() {
        return ChronoUnit.DAYS.between(postedAt, LocalDate.now()) > EXPIRATION_DAYS;
    }
}
