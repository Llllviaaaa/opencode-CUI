package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.assistant-info")
public class AssistantInfoProperties {
    private String apiUrl;
    private String apiToken;
    private long cacheTtlSeconds = 300;
}
