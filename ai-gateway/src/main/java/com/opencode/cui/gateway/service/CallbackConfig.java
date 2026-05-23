package com.opencode.cui.gateway.service;

import lombok.Data;

/** Cloud route config resolved from assistant remoteProperty or skill-server SysConfig. */
@Data
public class CallbackConfig {
    private String ak;
    private String scope;
    /** "webhook" | "sse" | "websocket" */
    private String channelType;
    private String channelAddress;
    /** "none" | "soa" | "apig" */
    private String authType;
    /** v1 由 hisAppId 映射；v2 为 null */
    private String appId;
}
