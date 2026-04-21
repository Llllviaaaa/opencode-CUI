package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 个人助手路由策略。
 *
 * <p>封装现有 SkillRelayService 的本地 Agent 转发逻辑。
 * route 方法为 no-op，因为 personal scope 的消息仍由 SkillRelayService 原有流程处理。
 * onRelay 参数在此策略中无需使用。</p>
 */
@Slf4j
@Component
public class PersonalInvokeRouteStrategy implements InvokeRouteStrategy {

    @Override
    public String getScope() {
        return "personal";
    }

    @Override
    public void route(GatewayMessage message, Consumer<GatewayMessage> onRelay) {
        // no-op: personal scope 消息由 SkillRelayService 原有的下行路由逻辑处理
        log.debug("[INVOKE_ROUTE] Personal scope, passthrough: ak={}", message.getAk());
    }
}
