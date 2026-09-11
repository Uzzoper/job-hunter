package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA {@link AttributeConverter} mapping the sealed {@link WorkPreference} sum
 * type to discriminated JSON text in the {@code work_preference} column
 * (e.g. {@code {"type":"hybrid","cities":["Curitiba","São Paulo"]}}).
 *
 * <p>Spec: docs/specs/user-preferences.md (sum-type evolution — V7).
 * Follows the {@link StringListConverter} precedent. Read direction is
 * <em>poisoned-read tolerant</em>: unknown type, malformed JSON, or a
 * structurally invalid variant (e.g. hybrid without cities from a hand-edited
 * DB) is caught, logged at WARN, and yields {@code null} instead of failing the
 * whole profile read.
 */
@Converter
public class WorkPreferenceConverter implements AttributeConverter<WorkPreference, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(WorkPreferenceConverter.class);

    /**
     * Serializes the variant to compact discriminated JSON; {@code null} maps to {@code null}.
     */
    @Override
    public String convertToDatabaseColumn(WorkPreference attribute) {
        if (attribute == null) {
            return null;
        }
        var node = OBJECT_MAPPER.createObjectNode();
        switch (attribute) {
            case WorkPreference.Remote r -> node.put("type", "remote");
            case WorkPreference.Hybrid h -> {
                node.put("type", "hybrid");
                node.set("cities", stringsToArray(h.cities()));
            }
            case WorkPreference.Onsite o -> {
                node.put("type", "onsite");
                node.set("cities", stringsToArray(o.cities()));
            }
        }
        return node.toString();
    }

    /**
     * Parses the discriminated JSON back into a {@link WorkPreference};
     * {@code null}/{@code blank} map to {@code null}, and any invalid payload
     * degrades to {@code null} (with a WARN log) instead of throwing.
     */
    @Override
    public WorkPreference convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dbData);
            String type = root.path("type").asText();
            List<String> cities = readCities(root.path("cities"));
            return switch (type) {
                case "remote" -> new WorkPreference.Remote();
                case "hybrid" -> new WorkPreference.Hybrid(cities);
                case "onsite" -> new WorkPreference.Onsite(cities);
                default -> throw new IllegalArgumentException("unknown work_preference type: " + type);
            };
        } catch (Exception e) {
            log.warn("Dropping invalid work_preference value '{}' stored in database — treating as unset",
                    truncate(dbData, 120));
            return null;
        }
    }

    private static ArrayNode stringsToArray(List<String> values) {
        var array = OBJECT_MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    /**
     * Reads the {@code cities} array from the stored node; malformed or
     * non-array values yield an empty list (validation then happens in the
     * variant compact constructors, which is also the poisoned-read safety net).
     */
    private static List<String> readCities(JsonNode citiesNode) {
        List<String> cities = new ArrayList<>();
        if (citiesNode.isArray()) {
            for (JsonNode city : citiesNode) {
                if (city.isTextual() && !city.asText().isBlank()) {
                    cities.add(city.asText().trim());
                }
            }
        }
        return cities;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}