package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentAvailabilityResponse(
        boolean exists,
        boolean online,
        String latestToolType,
        LocalDateTime lastSeenAt) {
}
