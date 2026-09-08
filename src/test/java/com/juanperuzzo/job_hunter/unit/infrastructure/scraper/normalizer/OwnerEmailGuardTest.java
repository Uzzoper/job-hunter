package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.OwnerEmailGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OwnerEmailGuard tests")
class OwnerEmailGuardTest {

    private static final String OWNER_EMAIL = "user@example.org";
    private static final String SECOND_OWNER_EMAIL = "second.owner@example.org";
    private static final String COMPANY_EMAIL = "hiring@techcorp.com";
    private static final String JOB_URL = "https://example.org/jobs/1";

    private final UserRepository userRepository = mock(UserRepository.class);
    private OwnerEmailGuard guard;

    @BeforeEach
    void setUp() {
        when(userRepository.findAllEmails()).thenReturn(List.of(OWNER_EMAIL, SECOND_OWNER_EMAIL));
        guard = new OwnerEmailGuard(userRepository);
    }

    @Nested
    @DisplayName("guard the extracted email")
    class Guard {

        @Test
        @DisplayName("should discard an email that matches the owner email exactly")
        void discard_whenOwnerMatch_shouldReturnNull() {
            assertNull(guard.discardIfOwnerEmail(OWNER_EMAIL, JOB_URL));
        }

        @Test
        @DisplayName("should discard an email that matches an owner email case-insensitively")
        void discard_whenCaseVariantOfOwner_shouldReturnNull() {
            assertNull(guard.discardIfOwnerEmail("User@Example.ORG", JOB_URL));
        }

        @Test
        @DisplayName("should discard an email that matches a second owner in the set")
        void discard_whenSecondOwnerMatch_shouldReturnNull() {
            assertNull(guard.discardIfOwnerEmail("Second.Owner@Example.ORG", JOB_URL));
        }

        @Test
        @DisplayName("should keep a distinct company email")
        void discard_whenDistinctCompanyEmail_shouldReturnUnchanged() {
            assertEquals(COMPANY_EMAIL, guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));
        }

        @Test
        @DisplayName("should return null unchanged when the candidate email is null")
        void discard_whenNullEmail_shouldReturnNull() {
            assertNull(guard.discardIfOwnerEmail(null, JOB_URL));
        }

        @Test
        @DisplayName("should return a blank candidate unchanged (no match attempt)")
        void discard_whenBlankEmail_shouldReturnItUnchanged() {
            assertEquals("   ", guard.discardIfOwnerEmail("   ", JOB_URL));
        }
    }

    @Nested
    @DisplayName("owner email set loading")
    class OwnerSet {

        @Test
        @DisplayName("should ignore null and blank owner entries when building the set")
        void ownerSet_whenNullAndBlankEntries_shouldIgnoreThem() {
            when(userRepository.findAllEmails()).thenReturn(Arrays.asList(null, "   ", OWNER_EMAIL));

            assertNull(guard.discardIfOwnerEmail(OWNER_EMAIL, JOB_URL));
            assertEquals(COMPANY_EMAIL, guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));
        }

        @Test
        @DisplayName("should treat a null owner list as an empty set (nil-guard)")
        void ownerSet_whenNullList_shouldKeepDistinctEmails() {
            when(userRepository.findAllEmails()).thenReturn(null);

            assertEquals(COMPANY_EMAIL, guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));
        }

        @Test
        @DisplayName("should trim surrounding whitespace from owner entries before matching")
        void ownerSet_whenOwnerEntriesHaveSurroundingWhitespace_shouldStillMatch() {
            when(userRepository.findAllEmails()).thenReturn(List.of("  user@example.org  "));

            assertNull(guard.discardIfOwnerEmail("User@Example.ORG", JOB_URL));
        }

        @Test
        @DisplayName("should load the owner email set only once and reuse the cache")
        void ownerSet_whenMultipleCalls_shouldLoadOnlyOnce() {
            guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL);
            guard.discardIfOwnerEmail(SECOND_OWNER_EMAIL, JOB_URL);
            guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL);

            verify(userRepository, times(1)).findAllEmails();
        }
    }

    @Nested
    @DisplayName("owner email load failure (DB unreadable)")
    class LoadFailure {

        @Test
        @DisplayName("should fail closed: discard the contact email and never throw")
        void discard_whenOwnerLoadFails_shouldFailClosedAndDiscardEmail() {
            when(userRepository.findAllEmails()).thenThrow(new RuntimeException("db down"));

            assertNull(guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));
        }

        @Test
        @DisplayName("should retry the load on the next call (retry-while-down)")
        void discard_whenOwnerLoadFails_shouldRetryOnNextCall() {
            when(userRepository.findAllEmails())
                    .thenThrow(new RuntimeException("db down"))
                    .thenReturn(List.of(OWNER_EMAIL));

            // First call: load fails → fail-closed, a distinct email is discarded.
            assertNull(guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));
            // Second call: load succeeds and is cached → the owner match is now detected.
            assertNull(guard.discardIfOwnerEmail(OWNER_EMAIL, JOB_URL));
            // Third call: cache hit → a distinct company email is kept.
            assertEquals(COMPANY_EMAIL, guard.discardIfOwnerEmail(COMPANY_EMAIL, JOB_URL));

            verify(userRepository, times(2)).findAllEmails();
        }
    }
}