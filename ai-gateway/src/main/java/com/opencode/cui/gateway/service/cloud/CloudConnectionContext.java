package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 云端连接上下文，封装建立云端连接所需的全部参数。
 */
@Data
@Builder
public class CloudConnectionContext {

    /** 云端服务地址 */
    private String endpoint;

    /** 发送给云端的请求体（JSON） */
    private JsonNode cloudRequest;

    /** 云端应用 ID */
    private String appId;

    /** 认证类型（soa / apig） */
    private String authType;

    /** 跨服务追踪 ID */
    private String traceId;
}
