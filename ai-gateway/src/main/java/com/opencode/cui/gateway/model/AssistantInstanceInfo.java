package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 数字分身实例信息。
 *
 * <p>对应 instance/query 接口的 data 节点。gateway 只消费远端调用所需字段，
 * 未列出的上游字段通过 ignoreUnknown 兼容。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantInstanceInfo {

    public static final int REMOTE_TYPE_LOCAL = 0;
    public static final int REMOTE_TYPE_ASSISTANT_SQUARE = 1;
    public static final int REMOTE_TYPE_DEFAULT = 2;
    public static final String PROFILE_ASSISTANT_SQUARE = "assistant_square";
    public static final String PROFILE_DEFAULT = "default";

    private String partnerAccount;
    private String ownerWelinkId;
    private String appKey;
    private Boolean isRemote;
    private Integer remoteType;
    private String bizRobotTag;
    private Integer userType;
    private List<RemoteProperty> remoteProperty;

    @JsonIgnore
    public boolean remoteAssistant() {
        if (remoteType != null) {
            return remoteType == REMOTE_TYPE_ASSISTANT_SQUARE
                    || remoteType == REMOTE_TYPE_DEFAULT;
        }
        return Boolean.TRUE.equals(isRemote)
                || (remoteProperty != null && !remoteProperty.isEmpty());
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
