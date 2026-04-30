package com.juanperuzzo.job_hunter.unit.domain;

import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Job domain model tests")
class JobTest {

    @Nested
    @DisplayName("equals and hashCode based on URL")
    class EqualsAndHashCodeTests {

        @Test
        @DisplayName("two jobs with identical URLs should be considered equal")
        void givenTwoJobsWithSameUrl_whenEquals_thenTheyAreEqual() {
            var postedAt = LocalDate.now().minusDays(10);
            var job1 = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description 1", postedAt, Optional.empty());
            var job2 = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description 2", postedAt, Optional.empty());

            assertEquals(job1, job2);
            assertEquals(job1.hashCode(), job2.hashCode());
        }

        @Test
        @DisplayName("two jobs with different URLs should be considered different")
        void givenTwoJobsWithDifferentUrls_whenEquals_thenTheyAreNotEqual() {
            var postedAt = LocalDate.now().minusDays(10);
            var job1 = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", postedAt, Optional.empty());
            var job2 = new Job(null, "Java Developer", "Company A", "https://example.com/job/456", "Description", postedAt, Optional.empty());

            assertNotEquals(job1, job2);
        }

        @Test
        @DisplayName("job should not be equal to null")
        void givenJob_whenComparedToNull_thenNotEqual() {
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", LocalDate.now(), Optional.empty());

            assertNotEquals(null, job);
        }

        @Test
        @DisplayName("job should not be equal to object of different type")
        void givenJob_whenComparedToDifferentType_thenNotEqual() {
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", LocalDate.now(), Optional.empty());

            assertNotEquals("not a job", job);
        }
    }

    @Nested
    @DisplayName("isExpired method")
    class IsExpiredTests {

        @Test
        @DisplayName("job within expiration window (20 days) should not be expired")
        void givenJobPosted20DaysAgo_whenIsExpired_thenReturnsFalse() {
            var postedAt = LocalDate.now().minusDays(20);
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", postedAt, Optional.empty());

            assertFalse(job.isExpired());
        }

        @Test
        @DisplayName("job past expiration window (31 days) should be expired")
        void givenJobPosted31DaysAgo_whenIsExpired_thenReturnsTrue() {
            var postedAt = LocalDate.now().minusDays(31);
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", postedAt, Optional.empty());

            assertTrue(job.isExpired());
        }

        @Test
        @DisplayName("job exactly at the limit (30 days) should not be expired")
        void givenJobPostedExactly30DaysAgo_whenIsExpired_thenReturnsFalse() {
            var postedAt = LocalDate.now().minusDays(30);
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", postedAt, Optional.empty());

            assertFalse(job.isExpired());
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("creating job with null URL should throw NullPointerException")
        void givenNullUrl_whenCreatingJob_thenThrowsNullPointerException() {
            assertThrows(NullPointerException.class, () ->
                new Job(null, "Java Developer", "Company A", null, "Description", LocalDate.now(), Optional.empty())
            );
        }

        @Test
        @DisplayName("creating job with null postedAt should throw NullPointerException")
        void givenNullPostedAt_whenCreatingJob_thenThrowsNullPointerException() {
            assertThrows(NullPointerException.class, () ->
                new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", null, Optional.empty())
            );
        }
    }

    @Nested
    @DisplayName("matchScore as Optional")
    class MatchScoreTests {

        @Test
        @DisplayName("job without matchScore should have empty Optional")
        void givenJobWithoutMatchScore_whenGetMatchScore_thenReturnsEmpty() {
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", LocalDate.now(), Optional.empty());

            assertTrue(job.matchScore().isEmpty());
        }

        @Test
        @DisplayName("job with matchScore should return present Optional")
        void givenJobWithMatchScore_whenGetMatchScore_thenReturnsValue() {
            var job = new Job(null, "Java Developer", "Company A", "https://example.com/job/123", "Description", LocalDate.now(), Optional.of(85));

            assertTrue(job.matchScore().isPresent());
            assertEquals(85, job.matchScore().get());
        }
    }
}
