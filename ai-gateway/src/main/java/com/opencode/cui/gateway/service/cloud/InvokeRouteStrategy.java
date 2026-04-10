package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;

import java.util.function.Consumer;

/**
 * Invoke 消息路由策略接口。
 *
 * <p>根据助手作用域（assistantScope）将 invoke 消息路由到不同的处理逻辑：
 * <ul>
 *   <li>{@code personal} — 本地 Agent 转发（原有逻辑）</li>
 *   <li>{@code business} — 云端 Agent 服务</li>
 * </ul>
 * </p>
 */
public interface InvokeRouteStrategy {

    /**
     * 返回当前策略支持的作用域标识。
     *
     * @return 作用域标识，如 {@code "personal"} 或 {@code "business"}
     */
    String getScope();

    /**
     * 路由 invoke 消息到对应的处理逻辑。
     *
     * @param message invoke 消息
     * @param onRelay 回调：当需要将消息转发回 SkillRelayService 时调用
     */
    void route(GatewayMessage message, Consumer<GatewayMessage> onRelay);
}
