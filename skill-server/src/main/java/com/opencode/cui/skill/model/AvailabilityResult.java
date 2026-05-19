package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AvailabilityResult(
        @JsonProperty("online") boolean online,
        @JsonProperty("message") String message,
        @JsonProperty("toolType") String toolType,
        @JsonProperty("source") AvailabilitySource source) {

    public static AvailabilityResult ofOnline() {
        return new AvailabilityResult(true, null, null, AvailabilitySource.ONLINE);
    }

    public static AvailabilityResult ofOfflineTyped(String message, String toolType) {
        return new AvailabilityResult(false, message, toolType, AvailabilitySource.OFFLINE_TYPED);
    }

    public static AvailabilityResult ofOfflineDefault(String message, String toolType) {
        return new AvailabilityResult(false, message, toolType, AvailabilitySource.OFFLINE_DEFAULT);
    }

    public static AvailabilityResult ofNotConfigured(String message) {
        return new AvailabilityResult(false, message, null, AvailabilitySource.NOT_CONFIGURED);
    }

    public static AvailabilityResult ofFallbackError(String message) {
        return new AvailabilityResult(false, message, null, AvailabilitySource.FALLBACK_ERROR);
    }
}
