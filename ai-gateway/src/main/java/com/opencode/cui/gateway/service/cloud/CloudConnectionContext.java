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

    /** 云端服务地址（v1: endpoint；v2: channelAddress） */
    private String channelAddress;

    /** 通道类型："webhook" / "sse" / "websocket" */
    private String channelType;

    /** 回调 scope（仅 v2 模式有值，例如 "callback:weagent:chat"） */
    private String scope;

    /** 发送给云端的请求体（JSON） */
    private JsonNode cloudRequest;

    /** 云端应用 ID（v1 由 hisAppId 映射；v2 为 null） */
    private String appId;

    /** 鉴权类型："none" / "soa" / "apig" / "integration_token" */
    private String authType;

    /** 跨服务追踪 ID */
    private String traceId;

    /**
     * 云端协议套餐名（profile name），由 SS 在 invoke payload 中传递。
     *
     * <p>用于 GW 端选择 SSE decoder。缺失时默认 {@code "default"}（兼容老 SS）。</p>
     */
    private String cloudProfile;
}
