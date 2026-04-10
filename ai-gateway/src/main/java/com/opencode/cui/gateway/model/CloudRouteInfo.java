package com.opencode.cui.gateway.model;

import lombok.Data;

/**
 * 云端路由信息。
 *
 * <p>由 CloudRouteService 从上游 API 获取并缓存，描述云端 AI 服务的接入配置。</p>
 */
@Data
public class CloudRouteInfo {

    /** 云端应用 ID（对应上游 API 响应中的 hisAppId） */
    private String appId;

    /** 云端服务地址 */
    private String endpoint;

    /** 通信协议，取值：{@code "sse"} | {@code "websocket"} */
    private String protocol;

    /** 鉴权类型，取值：{@code "soa"} | {@code "apig"} */
    private String authType;
}
