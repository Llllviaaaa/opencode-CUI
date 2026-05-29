package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 数字分身实例信息。
 *
 * <p>该模型对应 instance/query 接口的 data 节点，只承载当前链路需要的字段。
 * 未列出的字段通过 ignoreUnknown 兼容。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantInstanceInfo {

    public static final int REMOTE_TYPE_LOCAL = 0;
    public static final int REMOTE_TYPE_ASSISTANT_SQUARE = 1;
    public static final int REMOTE_TYPE_DEFAULT = 2;
    public static final String PROFILE_ASSISTANT_SQUARE = "assistant_square";
    public static final String PROFILE_DEFAULT = "default";

    private String id;
    private String partnerAccount;
    private String ownerWelinkId;
    private String createdBy;
    private String appKey;
    private Integer remoteType;
    private String bizRobotTag;
    private Integer userType;
    private List<RemoteProperty> remoteProperty;

    @JsonIgnore
    public boolean remoteAssistant() {
        return remoteType != null
                && (remoteType == REMOTE_TYPE_ASSISTANT_SQUARE
                || remoteType == REMOTE_TYPE_DEFAULT);
    }

    @JsonIgnore
    public boolean businessRoutableAssistant() {
        return remoteAssistant();
    }

    @JsonIgnore
    public String protocolProfile() {
        if (remoteType == null) {
            return null;
        }
        return switch (remoteType) {
            case REMOTE_TYPE_ASSISTANT_SQUARE -> PROFILE_ASSISTANT_SQUARE;
            case REMOTE_TYPE_DEFAULT -> PROFILE_DEFAULT;
            default -> null;
        };
    }

    @JsonIgnore
    public String effectiveAk() {
        return appKey != null && !appKey.isBlank() ? appKey : null;
    }

    @JsonIgnore
    public String effectivePartnerAccount(String fallback) {
        return partnerAccount != null && !partnerAccount.isBlank() ? partnerAccount : fallback;
    }

    @JsonIgnore
    public String effectiveOwnerUserId() {
        if (createdBy != null && !createdBy.isBlank()) {
            return createdBy;
        }
        return ownerWelinkId != null && !ownerWelinkId.isBlank() ? ownerWelinkId : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemoteProperty {
        private String url;
        private String dataProtocol;
        private String commProtocol;
        private String type;
        private List<RemoteHeader> headers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemoteHeader {
        private String type;
        private String customKey;
        private String customValue;
    }
}
