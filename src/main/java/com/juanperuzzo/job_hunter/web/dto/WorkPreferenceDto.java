package com.juanperuzzo.job_hunter.web.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Discriminated JSON representation of the {@link WorkPreference} sum type
 * exposed via the profile endpoints.
 *
 * <pre>
 * { "type": "remote" }
 * { "type": "hybrid", "cities": ["Curitiba", "São Paulo"] }
 * { "type": "onsite", "cities": ["Curitiba"] }
 * </pre>
 *
 * <p>The {@code type} property discriminates the variant. Bean validation
 * mirrors the domain caps: {@code hybrid}/{@code onsite} require a non-empty
 * city list (≤ 20 items, each ≤ 100 chars); {@code remote} carries no data.
 * The authoritative validation still lives in the domain compact constructors
 * invoked by {@link #toDomain()}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WorkPreferenceDto.Remote.class, name = "remote"),
        @JsonSubTypes.Type(value = WorkPreferenceDto.Hybrid.class, name = "hybrid"),
        @JsonSubTypes.Type(value = WorkPreferenceDto.Onsite.class, name = "onsite")
})
public sealed interface WorkPreferenceDto permits WorkPreferenceDto.Remote, WorkPreferenceDto.Hybrid, WorkPreferenceDto.Onsite {

    /**
     * Fully remote preference — no city data.
     */
    record Remote() implements WorkPreferenceDto {
        @Override
        public WorkPreference toDomain() {
            return new WorkPreference.Remote();
        }
    }

    /**
     * Hybrid preference with a required city list.
     *
     * @param cities on-site cities for the hybrid arrangement
     */
    record Hybrid(
            @NotEmpty(message = "cities must not be empty for hybrid")
            @Size(max = 20, message = "cities must contain at most 20 items")
            List<@Size(max = 100, message = "each city must be at most 100 characters") String> cities
    ) implements WorkPreferenceDto {
        @Override
        public WorkPreference toDomain() {
            return new WorkPreference.Hybrid(cities);
        }
    }

    /**
     * On-site preference with a required city list.
     *
     * @param cities on-site cities
     */
    record Onsite(
            @NotEmpty(message = "cities must not be empty for onsite")
            @Size(max = 20, message = "cities must contain at most 20 items")
            List<@Size(max = 100, message = "each city must be at most 100 characters") String> cities
    ) implements WorkPreferenceDto {
        @Override
        public WorkPreference toDomain() {
            return new WorkPreference.Onsite(cities);
        }
    }

    /**
     * Converts this DTO variant into the domain {@link WorkPreference},
     * applying the authoritative compact-constructor validation.
     */
    WorkPreference toDomain();
}