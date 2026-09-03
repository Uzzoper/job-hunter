package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.UrlNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("UrlNormalizer tests")
class UrlNormalizerTest {

    @Nested
    @DisplayName("noTrailingSlash")
    class NoTrailingSlash {

        @Test
        @DisplayName("should strip a single trailing slash")
        void shouldStripTrailingSlash() {
            assertEquals("https://example.com", UrlNormalizer.noTrailingSlash("https://example.com/"));
        }

        @Test
        @DisplayName("should preserve a URL without trailing slash")
        void shouldPreserveUrlWithoutTrailingSlash() {
            assertEquals("https://example.com", UrlNormalizer.noTrailingSlash("https://example.com"));
        }

        @Test
        @DisplayName("should strip only one trailing slash on a multi-slash URL")
        void shouldStripOnlyOneTrailingSlash() {
            assertEquals("https://example.com/", UrlNormalizer.noTrailingSlash("https://example.com//"));
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(UrlNormalizer.noTrailingSlash(null));
        }

        @Test
        @DisplayName("should return null for blank input")
        void shouldReturnNullForBlank() {
            assertNull(UrlNormalizer.noTrailingSlash("  "));
        }
    }

    @Nested
    @DisplayName("absolute")
    class Absolute {

        @Test
        @DisplayName("should resolve a relative URL against the base and strip trailing slash")
        void shouldResolveRelativeAgainstBase() {
            assertEquals("https://example.com/contato",
                    UrlNormalizer.absolute("/contato", "https://example.com"));
        }

        @Test
        @DisplayName("should return the absolute URL unchanged when already absolute")
        void shouldReturnAbsoluteUnchanged() {
            assertEquals("https://techcorp.com.br",
                    UrlNormalizer.absolute("https://techcorp.com.br", "https://example.com"));
        }

        @Test
        @DisplayName("should return null for blank input")
        void shouldReturnNullForBlank() {
            assertNull(UrlNormalizer.absolute("", "https://example.com"));
        }
    }
}
