package com.opencode.cui.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 云端连接超时配置。
 * 通过 {@code gateway.cloud.*} 配置前缀绑定。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.cloud")
public class CloudTimeoutProperties {

    /** TCP + TLS 建联超时（秒） */
    private int connectTimeoutSeconds = 30;

    /** 建联成功到首条数据/心跳的等待超时（秒） */
    private int firstEventTimeoutSeconds = 120;

    /** 最后一次收到数据/心跳后的空闲超时（秒） */
    private int idleTimeoutSeconds = 90;

    /** 单轮对话最大总时长（秒） */
    private int maxDurationSeconds = 600;

    /** SSE 协议覆盖配置 */
    private ProtocolOverride sse = new ProtocolOverride();

    /** WebSocket 协议覆盖配置 */
    private WsOverride websocket = new WsOverride();

    /**
     * 获取指定协议的有效空闲超时。协议级覆盖优先，未设置则用通用值。
     */
    public int getEffectiveIdleTimeoutSeconds(String protocol) {
        if ("websocket".equals(protocol) && websocket.getIdleTimeoutSeconds() != null) {
            return websocket.getIdleTimeoutSeconds();
        }
        if ("sse".equals(protocol) && sse.getIdleTimeoutSeconds() != null) {
            return sse.getIdleTimeoutSeconds();
        }
        return idleTimeoutSeconds;
    }

    @Getter
    @Setter
    public static class ProtocolOverride {
        /** 协议级空闲超时覆盖（null 表示使用通用值） */
        private Integer idleTimeoutSeconds;
    }

    @Getter
    @Setter
    public static class WsOverride extends ProtocolOverride {
        /** WebSocket Ping 发送间隔（秒） */
        private int pingIntervalSeconds = 30;
    }
}
