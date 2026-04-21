package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class AssistantInfo {
    private String assistantScope;    // "business" | "personal"
    private String appId;
    private String cloudEndpoint;
    private String cloudProtocol;     // "sse" | "websocket"
    private String authType;          // "soa" | "apig"

    @JsonIgnore
    public boolean isBusiness() {
        return "business".equals(assistantScope);
    }

    @JsonIgnore
    public boolean isPersonal() {
        return !isBusiness();
    }
}
