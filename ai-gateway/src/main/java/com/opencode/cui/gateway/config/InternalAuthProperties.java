package com.opencode.cui.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Slf4j
@Getter
@ConfigurationProperties(prefix = "skill.gateway")
public class InternalAuthProperties {

    private final String internalToken;

    public InternalAuthProperties(String internalToken) {
        this.internalToken = internalToken;
    }

    @PostConstruct
    void validate() {
        if (internalToken == null || internalToken.isBlank() || "changeme".equals(internalToken)) {
            log.error("[FATAL] skill.gateway.internal-token is not configured or still set to 'changeme'. "
                    + "Internal API authentication must be properly configured for security.");
            throw new IllegalStateException(
                    "skill.gateway.internal-token must be set to a non-empty value different from 'changeme'");
        }
    }
}
