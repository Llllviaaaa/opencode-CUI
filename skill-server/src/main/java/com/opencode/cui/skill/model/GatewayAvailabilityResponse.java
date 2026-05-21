package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayAvailabilityResponse(
        boolean exists,
        boolean online,
        String latestToolType,
        String lastSeenAt) {
}
