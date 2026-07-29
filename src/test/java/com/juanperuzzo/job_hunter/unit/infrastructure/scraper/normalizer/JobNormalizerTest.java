package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class JobNormalizerTest {

    private static final String VALID_EMAIL = "hiring@techcorp.com";

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T00:00:00Z"), ZoneId.of("UTC"));

    private JobNormalizer normalizer;
    private DateParser dateParser;

    @BeforeEach
    void setUp() {
        dateParser = new DateParser(FIXED_CLOCK);
    }

    @Nested
    @DisplayName("cleanText")
    class CleanText {

        @Test
        @DisplayName("should trim whitespace")
        void shouldTrimWhitespace() {
            assertEquals("foo bar", JobNormalizer.cleanText("  foo bar  "));
        }

        @Test
        @DisplayName("should collapse consecutive whitespace")
        void shouldCollapseConsecutiveWhitespace() {
            assertEquals("foo bar", JobNormalizer.cleanText("foo   bar"));
        }

        @Test
        @DisplayName("should return empty for null input")
        void shouldReturnEmptyForNull() {
            assertEquals("", JobNormalizer.cleanText(null));
        }

        @Test
        @DisplayName("should return empty for blank input")
        void shouldReturnEmptyForBlank() {
            assertEquals("", JobNormalizer.cleanText("   "));
        }
    }

    @Nested
    @DisplayName("normalizeText")
    class NormalizeText {

        @Test
        @DisplayName("should decompose NFD and remove diacritics")
        void shouldRemoveDiacritics() {
            assertEquals("analista desenvolvimento", JobNormalizer.normalizeText("Analista Desenvolvimento"));
            assertEquals("gerente producao", JobNormalizer.normalizeText("Gerente Produção"));
            assertEquals("estagiario ti", JobNormalizer.normalizeText("Estagiário TI"));
        }

        @Test
        @DisplayName("should lowercase input")
        void shouldLowercase() {
            assertEquals("desenvolvedor java", JobNormalizer.normalizeText("DESENVOLVEDOR Java"));
        }

        @Test
        @DisplayName("should expand jr to junior")
        void shouldExpandJr() {
            assertEquals("desenvolvedor junior", JobNormalizer.normalizeText("Desenvolvedor Jr"));
            assertEquals("junior java developer", JobNormalizer.normalizeText("Jr Java Developer"));
        }

        @Test
        @DisplayName("should expand sr to senior")
        void shouldExpandSr() {
            assertEquals("desenvolvedor senior", JobNormalizer.normalizeText("Desenvolvedor Sr"));
        }

        @Test
        @DisplayName("should expand pl to pleno")
        void shouldExpandPl() {
            assertEquals("analista pleno", JobNormalizer.normalizeText("Analista Pl"));
        }

        @Test
        @DisplayName("should not expand partial matches")
        void shouldNotExpandPartial() {
            assertEquals("projeto", JobNormalizer.normalizeText("Projeto"));
            assertEquals("jornal", JobNormalizer.normalizeText("Jornal"));
        }
    }

    @Nested
    @DisplayName("normalize - keyword matching")
    class KeywordMatching {

        @Test
        @DisplayName("should accept when no keywords configured")
        void shouldAcceptWhenNoKeywords() {
            normalizer = new JobNormalizer(dateParser, List.of(), List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job1",
                    "Description", "2026-07-01", "São Paulo", "Presencial", "test", null));
            assertNotNull(job);
            assertEquals("Desenvolvedor Java", job.title());
        }

        @Test
        @DisplayName("should accept when keyword matches title")
        void shouldAcceptWhenKeywordMatchesTitle() {
            normalizer = new JobNormalizer(dateParser,
                    List.of("Java", "Spring"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java Spring", "Company", "https://example.com/job2",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNotNull(job);
        }

        @Test
        @DisplayName("should reject when no keyword matches")
        void shouldRejectWhenNoKeywordMatches() {
            normalizer = new JobNormalizer(dateParser,
                    List.of("Python"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job3",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNull(job);
        }

        @Test
        @DisplayName("should match multi-token keywords like 'junior java'")
        void shouldMatchMultiTokenKeywords() {
            normalizer = new JobNormalizer(dateParser,
                    List.of("Junior Java"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java Jr", "Company", "https://example.com/job4",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNotNull(job);
        }

        @Test
        @DisplayName("should match keyword in description when not in title")
        void shouldMatchKeywordInDescription() {
            normalizer = new JobNormalizer(dateParser,
                    List.of("Kubernetes"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Backend", "Company", "https://example.com/job5",
                    "We use Kubernetes for orchestration",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
        }
    }

    @Nested
    @DisplayName("normalize - exclude patterns")
    class ExcludePatterns {

        @Test
        @DisplayName("should reject when title matches exclude pattern")
        void shouldRejectWhenExcluded() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(Pattern.compile("(?<![\\p{L}\\p{N}_])senior(?![\\p{L}\\p{N}_])",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS)),
                    List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Senior", "Company", "https://example.com/excluded1",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNull(job);
        }

        @Test
        @DisplayName("should accept when exclude pattern does not match")
        void shouldAcceptWhenNotExcluded() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(Pattern.compile("senior"), Pattern.compile("pleno")),
                    List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Junior", "Company", "https://example.com/not-excluded",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNotNull(job);
        }
    }

    @Nested
    @DisplayName("normalize - location matching")
    class LocationMatching {

        @Test
        @DisplayName("should accept when no locations configured")
        void shouldAcceptWhenNoLocations() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/loc1",
                    "Description", "2026-07-01", "São Paulo", "Presencial", "test", null));
            assertNotNull(job);
        }

        @Test
        @DisplayName("should accept remote jobs regardless of location filter")
        void shouldAcceptRemoteJobs() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of("São Paulo"), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/remote1",
                    "Description", "2026-07-01", "Qualquer", "Remoto", "test", null));
            assertNotNull(job);
        }

        @Test
        @DisplayName("should accept when location matches")
        void shouldAcceptWhenLocationMatches() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of("sao paulo"), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/sp",
                    "Description", "2026-07-01", "São Paulo", "Presencial", "test", null));
            assertNotNull(job);
        }

        @Test
        @DisplayName("should reject when location does not match")
        void shouldRejectWhenLocationDoesNotMatch() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of("São Paulo"), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/rj",
                    "Description", "2026-07-01", "Rio de Janeiro", "Presencial", "test", null));
            assertNull(job);
        }
    }

    @Nested
    @DisplayName("normalize - date filtering")
    class DateFiltering {

        @Test
        @DisplayName("should accept jobs within maxAgeDays")
        void shouldAcceptRecentJobs() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/recent",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertEquals(LocalDate.of(2026, 7, 1), job.postedAt());
        }

        @Test
        @DisplayName("should reject jobs older than maxAgeDays")
        void shouldRejectOldJobs() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 30, FIXED_CLOCK);
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/old",
                    "Description", "2026-05-01", null, null, "test", null));
            assertNull(job);
        }
    }

    @Nested
    @DisplayName("normalize - edge cases")
    class EdgeCases {

        @BeforeEach
        void setUp() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
        }

        @Test
        @DisplayName("should return null for null title")
        void shouldReturnNullForNullTitle() {
            var job = normalizer.normalize(new RawJob(
                    null, "Company", "https://example.com/null-title",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNull(job);
        }

        @Test
        @DisplayName("should return null for blank title")
        void shouldReturnNullForBlankTitle() {
            var job = normalizer.normalize(new RawJob(
                    "  ", "Company", "https://example.com/blank-title",
                    "Description", "2026-07-01", null, null, "test", null));
            assertNull(job);
        }

        @Test
        @DisplayName("should handle null rawDate")
        void shouldHandleNullDate() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/null-date",
                    "Description", null, null, null, "test", null));
            assertNull(job);
        }

        @Test
        @DisplayName("should handle null description")
        void shouldHandleNullDescription() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor", "Company", "https://example.com/null-desc",
                    null, "2026-07-01", null, null, "test", null));
            assertNotNull(job);
        }
    }

    @Nested
    @DisplayName("normalize - contact email extraction")
    class ContactEmailExtraction {

        @BeforeEach
        void setUp() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
        }

        @Test
        @DisplayName("should extract email from description")
        void shouldExtractEmailFromDescription() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Send your resume to " + VALID_EMAIL,
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertEquals(VALID_EMAIL, job.contactEmail());
        }

        @Test
        @DisplayName("should extract first email when description has multiple")
        void shouldExtractFirstEmailWhenMultiple() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Contact joao@empresa.com or rh@empresa.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertEquals("joao@empresa.com", job.contactEmail());
        }

        @Test
        @DisplayName("should return null when description has no email")
        void shouldReturnNullWhenNoEmail() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Apply through our website",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should extract email from title when not in description")
        void shouldExtractEmailFromTitle() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor — contact@startup.io", "Company", "https://example.com/job",
                    "Apply online",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertEquals("contact@startup.io", job.contactEmail());
        }

        @Test
        @DisplayName("should ignore noreply email")
        void shouldIgnoreNoreply() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Do not reply — noreply@company.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore donotreply email")
        void shouldIgnoreDonotreply() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Auto-generated, donotreply@company.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore apply email")
        void shouldIgnoreApplyEmail() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Submit via apply@company.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore placeholder example.com email")
        void shouldIgnorePlaceholderExample() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Email us at exemplo@exemplo.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore no-reply email with hyphen")
        void shouldIgnoreNoReplyWithHyphen() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Auto reply, no-reply@company.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore placeholder test.com email")
        void shouldIgnorePlaceholderTestCom() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Contact us at job@test.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should ignore placeholder domain.com email")
        void shouldIgnorePlaceholderDomainCom() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    "Send to hr@domain.com",
                    "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }

        @Test
        @DisplayName("should return null when description is null")
        void shouldReturnNullWhenDescriptionIsNull() {
            var job = normalizer.normalize(new RawJob(
                    "Desenvolvedor Java", "Company", "https://example.com/job",
                    null, "2026-07-01", null, null, "test", null));
            assertNotNull(job);
            assertNull(job.contactEmail());
        }
    }

    @Nested
    @DisplayName("normalizeAll")
    class NormalizeAll {

        @Test
        @DisplayName("should normalize multiple raw jobs")
        void shouldNormalizeMultipleJobs() {
            normalizer = new JobNormalizer(dateParser, List.of("desenvolvedor"),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            var jobs = normalizer.normalizeAll(List.of(
                    new RawJob("Desenvolvedor Java", "C1", "https://a.com/1",
                            "Desc", "2026-07-01", null, null, "test", null),
                    new RawJob("Analista de Dados", "C2", "https://a.com/2",
                            "Desc", "2026-07-01", null, null, "test", null),
                    new RawJob("Desenvolvedor Python", "C3", "https://a.com/3",
                            "Desc", "2026-07-01", null, null, "test", null)
            ));
            assertEquals(2, jobs.size());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyForEmptyInput() {
            normalizer = new JobNormalizer(dateParser, List.of(),
                    List.of(), List.of(), 90, FIXED_CLOCK);
            assertTrue(normalizer.normalizeAll(List.of()).isEmpty());
        }
    }
}
