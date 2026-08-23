package com.juanperuzzo.job_hunter.unit.infrastructure.persistence;

import com.juanperuzzo.job_hunter.infrastructure.persistence.StringListConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link StringListConverter} (spec: docs/specs/sqlite-local-persistence.md,
 * Scenario 2 — string-list columns round-trip).
 *
 * <p>Plain JUnit 5 — no Spring, no Mockito (unit-layer rules from AGENTS.md).
 */
@DisplayName("StringListConverter tests")
class StringListConverterTest {

    private StringListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringListConverter();
    }

    @Test
    @DisplayName("convertToDatabaseColumn should return null when the attribute is null")
    void convertToDatabaseColumn_whenNull_shouldReturnNull() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    @DisplayName("convertToDatabaseColumn should serialize an array as compact JSON text")
    void convertToDatabaseColumn_whenArray_shouldReturnJsonText() {
        var skills = new String[]{"Java", "Spring"};

        var json = converter.convertToDatabaseColumn(skills);

        assertEquals("[\"Java\",\"Spring\"]", json);
    }

    @Test
    @DisplayName("convertToDatabaseColumn should return an empty JSON array for an empty array")
    void convertToDatabaseColumn_whenEmptyArray_shouldReturnEmptyJsonArray() {
        assertEquals("[]", converter.convertToDatabaseColumn(new String[0]));
    }

    @Test
    @DisplayName("convertToEntityAttribute should return null when the database value is null")
    void convertToEntityAttribute_whenNull_shouldReturnNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    @DisplayName("convertToEntityAttribute should parse JSON text back into a String array")
    void convertToEntityAttribute_whenJsonText_shouldReturnArray() {
        var skills = converter.convertToEntityAttribute("[\"Java\",\"Spring\"]");

        assertArrayEquals(new String[]{"Java", "Spring"}, skills);
    }

    @Test
    @DisplayName("round trip through both conversion directions should preserve all values")
    void convert_whenRoundTrip_shouldPreserveValues() {
        var original = new String[]{"Java", "Spring Boot", "PostgreSQL"};

        var json = converter.convertToDatabaseColumn(original);
        var result = converter.convertToEntityAttribute(json);

        assertArrayEquals(original, result);
    }
}
