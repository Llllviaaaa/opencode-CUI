package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.CloudAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 业务助手路由策略。
 *
 * <p>将 invoke 消息委托给 {@link CloudAgentService} 处理，
 * 通过云端服务完成 AI 对话。</p>
 *
 * <p>{@code onRelay} 回调由 {@link com.opencode.cui.gateway.service.SkillRelayService} 传入，
 * 透传给 CloudAgentService，使得云端响应可回流到 SkillRelayService，
 * 同时避免直接依赖 SkillRelayService 造成循环依赖。</p>
 */
@Slf4j
@Component
public class BusinessInvokeRouteStrategy implements InvokeRouteStrategy {

    private final CloudAgentService cloudAgentService;

    public BusinessInvokeRouteStrategy(CloudAgentService cloudAgentService) {
        this.cloudAgentService = cloudAgentService;
    }

    @Override
    public String getScope() {
        return "business";
    }

    @Override
    public void route(GatewayMessage message, Consumer<GatewayMessage> onRelay) {
        log.info("[INVOKE_ROUTE] Business scope, delegating to CloudAgentService: ak={}", message.getAk());
        cloudAgentService.handleInvoke(message, onRelay);
    }
}
