package com.juanperuzzo.job_hunter.unit.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.infrastructure.config.DefaultKeywordResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultKeywordResolver tests")
class DefaultKeywordResolverTest {

    private static final long USER_ID = 7L;

    @Mock
    private UserProfileRepository userProfileRepository;

    private DefaultKeywordResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultKeywordResolver(userProfileRepository);
    }

    private UserProfile profileWith(UserPreferences preferences) {
        return new UserProfile(null, 1L, "resume text long enough", List.of("Java"),
                CompanyTone.FORMAL, List.of(), null, null, null, null, null, preferences);
    }

    @Nested
    @DisplayName("Scenario: stored keywords")
    class StoredKeywordsTests {

        @Test
        @DisplayName("resolve should return the stored keywords when present and non-empty")
        void resolve_whenKeywordsStored_shouldReturnStoredKeywords() {
            when(userProfileRepository.findByUserId(USER_ID))
                    .thenReturn(Optional.of(profileWith(
                            new UserPreferences(null, null, List.of(), List.of("rust", "kotlin")))));

            var result = resolver.resolve(USER_ID);

            assertEquals(List.of("rust", "kotlin"), result);
        }
    }

    @Nested
    @DisplayName("Scenario: no stored keywords resolves to empty (not the yaml default)")
    class NoStoredKeywordsTests {

        @Test
        @DisplayName("resolve should return an empty list when the profile has no preferences")
        void resolve_whenPreferencesNull_shouldReturnEmptyList() {
            when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profileWith(null)));

            var result = resolver.resolve(USER_ID);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("resolve should return an empty list when the stored keyword list is empty")
        void resolve_whenKeywordsEmpty_shouldReturnEmptyList() {
            when(userProfileRepository.findByUserId(USER_ID))
                    .thenReturn(Optional.of(profileWith(
                            new UserPreferences(null, null, List.of(), List.of()))));

            var result = resolver.resolve(USER_ID);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("resolve should return an empty list when no profile exists")
        void resolve_whenProfileMissing_shouldReturnEmptyList() {
            when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            var result = resolver.resolve(USER_ID);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario: invalid input")
    class InvalidInputTests {

        @Test
        @DisplayName("resolve should reject a null userId")
        void resolve_whenUserIdNull_shouldThrowNullPointerException() {
            assertThrows(NullPointerException.class, () -> resolver.resolve(null));
        }

        @Test
        @DisplayName("constructor should reject a null repository")
        void constructor_whenRepositoryNull_shouldThrowNullPointerException() {
            assertThrows(NullPointerException.class, () -> new DefaultKeywordResolver(null));
        }
    }
}
