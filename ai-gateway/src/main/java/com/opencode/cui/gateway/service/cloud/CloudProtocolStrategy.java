package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;

import java.util.function.Consumer;

/**
 * 云端通信协议策略接口。
 *
 * <p>不同云端服务使用不同协议（SSE / WebSocket），通过策略模式实现统一的连接抽象。</p>
 */
public interface CloudProtocolStrategy {

    /**
     * 返回当前策略支持的协议标识。
     *
     * @return 协议标识，如 {@code "sse"} 或 {@code "websocket"}
     */
    String getProtocol();

    /**
     * 连接云端服务并开始接收事件。
     *
     * @param context   连接上下文
     * @param lifecycle 连接生命周期管理器，用于超时计时；可为 null（向下兼容）
     * @param onEvent   事件回调，每收到一个云端消息时调用
     * @param onError   错误回调，连接异常时调用
     */
    void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                 Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError);
}
