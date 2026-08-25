package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} mapping {@code String[]} list columns to compact JSON text
 * (e.g. {@code ["Java","Spring"]}) so they fit in a single SQLite TEXT column.
 *
 * <p>Spec: docs/specs/sqlite-local-persistence.md (Rule 3 — one entity mapping).
 * Null-safe in both directions; NOT NULL columns are guarded by the schema.
 */
@Converter
public class StringListConverter implements AttributeConverter<String[], String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Serializes the array to compact JSON text; {@code null} maps to {@code null}.
     */
    @Override
    public String convertToDatabaseColumn(String[] attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize string list to JSON", e);
        }
    }

    /**
     * Parses the JSON text back into an array; {@code null} maps to {@code null}.
     */
    @Override
    public String[] convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, String[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize string list from JSON: " + dbData, e);
        }
    }
}
