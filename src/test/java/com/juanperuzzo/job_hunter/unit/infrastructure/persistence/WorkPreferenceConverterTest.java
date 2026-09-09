package com.juanperuzzo.job_hunter.unit.infrastructure.persistence;

import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import com.juanperuzzo.job_hunter.infrastructure.persistence.WorkPreferenceConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WorkPreferenceConverter} (spec: docs/specs/user-preferences.md,
 * sum-type evolution — V7 discriminated-JSON column).
 *
 * <p>Pins the <em>hostile-input contract</em>: any payload that does not map to a
 * valid sealed-variant (malformed JSON, missing/unknown {@code type}, or a
 * structurally invalid variant) degrades to {@code null} instead of failing the
 * profile read. The adapter-level equivalent is
 * {@code SqliteBaselineIntegrationTest.findByUserId_withGarbageWorkPreference_shouldReturnNullWorkPreference}.
 *
 * <p>Plain JUnit 5 — no Spring, no Mockito (unit-layer rules from AGENTS.md).
 */
@DisplayName("WorkPreferenceConverter tests")
class WorkPreferenceConverterTest {

    private WorkPreferenceConverter converter;

    @BeforeEach
    void setUp() {
        converter = new WorkPreferenceConverter();
    }

    // ── Convert-to-column ───────────────────────────────────────────

    @Test
    @DisplayName("convertToDatabaseColumn should return null when the attribute is null")
    void convertToDatabaseColumn_whenNull_shouldReturnNull() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    @DisplayName("convertToDatabaseColumn should serialize Remote as type-only JSON")
    void convertToDatabaseColumn_whenRemote_shouldSerializeDiscriminatedJson() {
        assertEquals("{\"type\":\"remote\"}",
                converter.convertToDatabaseColumn(new WorkPreference.Remote()));
    }

    @Test
    @DisplayName("convertToDatabaseColumn should serialize Hybrid with its cities array")
    void convertToDatabaseColumn_whenHybrid_shouldSerializeDiscriminatedJson() {
        assertEquals("{\"type\":\"hybrid\",\"cities\":[\"Curitiba\",\"São Paulo\"]}",
                converter.convertToDatabaseColumn(
                        new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo"))));
    }

    @Test
    @DisplayName("round trip through both directions should preserve every variant")
    void convert_whenRoundTrip_shouldPreserveVariants() {
        var variants = List.<WorkPreference>of(
                new WorkPreference.Remote(),
                new WorkPreference.Hybrid(List.of("Curitiba", "São Paulo")),
                new WorkPreference.Onsite(List.of("Curitiba")));
        for (var variant : variants) {
            String json = converter.convertToDatabaseColumn(variant);
            assertEquals(variant, converter.convertToEntityAttribute(json));
        }
    }

    // ── Convert-to-entity: null/blank ───────────────────────────────

    @Test
    @DisplayName("convertToEntityAttribute should return null when the database value is null")
    void convertToEntityAttribute_whenNull_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null when the database value is blank")
    void convertToEntityAttribute_whenBlank_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("   "));
    }

    // ── Hostile input: graceful null fallback (anti-hallucination) ──

    @Test
    @DisplayName("convertToEntityAttribute should degrade malformed JSON to null")
    void convertToEntityAttribute_whenMalformedJson_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("{not valid json"));
        assertNull(converter.convertToEntityAttribute(""));
    }

    @Test
    @DisplayName("convertToEntityAttribute should degrade a payload missing type to null")
    void convertToEntityAttribute_whenMissingType_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("{\"cities\":[\"Curitiba\"]}"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should degrade cities-as-string to null (hybrid cannot be built)")
    void convertToEntityAttribute_whenCitiesAsString_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("{\"type\":\"hybrid\",\"cities\":\"Curitiba\"}"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should degrade cities-as-object to null (onsite cannot be built)")
    void convertToEntityAttribute_whenCitiesAsObject_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("{\"type\":\"onsite\",\"cities\":{\"a\":1}}"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should degrade an unknown type even with valid-looking cities")
    void convertToEntityAttribute_whenUnknownTypeWithCities_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute(
                "{\"type\":\"banana\",\"cities\":[\"Curitiba\"]}"));
    }

    @Test
    @DisplayName("convertToEntityAttribute should degrade hybrid with an empty city array to null")
    void convertToEntityAttribute_whenHybridWithEmptyCities_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute("{\"type\":\"hybrid\",\"cities\":[]}"));
    }
}