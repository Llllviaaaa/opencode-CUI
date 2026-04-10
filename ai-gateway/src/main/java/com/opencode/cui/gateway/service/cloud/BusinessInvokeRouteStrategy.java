package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.CloudAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务助手路由策略。
 *
 * <p>将 invoke 消息委托给 {@link CloudAgentService} 处理，
 * 通过云端服务完成 AI 对话。</p>
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
    public void route(GatewayMessage message) {
        log.info("[INVOKE_ROUTE] Business scope, delegating to CloudAgentService: ak={}", message.getAk());
        cloudAgentService.handleInvoke(message);
    }
}
