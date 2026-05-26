package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/** Connection data needed to call a cloud assistant endpoint. */
@Data
@Builder
public class CloudConnectionContext {

    /** Cloud endpoint address. */
    private String channelAddress;

    /** Channel type: "webhook", "sse", or "websocket". */
    private String channelType;

    /** Callback scope, for example "callback:weagent:chat". */
    private String scope;

    /** Request body sent to the cloud endpoint. */
    private JsonNode cloudRequest;

    /** Cloud application ID. */
    private String appId;

    /** Auth type: "none", "soa", "apig", or "integration_token". */
    private String authType;

    /** Cross-service trace ID. */
    private String traceId;

    /** Protocol profile name used for cloud response decoding. */
    private String cloudProfile;

    /** Optional handle used by abort_session to close the active stream. */
    private CloudConnectionHandle connectionHandle;
}
