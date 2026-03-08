package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.ws.AgentWebSocketHandler;
import com.opencode.cui.gateway.ws.SkillWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class GatewayConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final SkillWebSocketHandler skillWebSocketHandler;

    public GatewayConfig(AgentWebSocketHandler agentWebSocketHandler,
            SkillWebSocketHandler skillWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.skillWebSocketHandler = skillWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .addInterceptors(agentWebSocketHandler)
                .setAllowedOrigins("*");

        registry.addHandler(skillWebSocketHandler, "/ws/internal/skill")
                .setAllowedOrigins("*");
    }
}
