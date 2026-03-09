package com.opencode.cui.skill.config;

import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class SkillConfig implements WebSocketConfigurer {

    private final SkillStreamHandler skillStreamHandler;

    public SkillConfig(SkillStreamHandler skillStreamHandler) {
        this.skillStreamHandler = skillStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(skillStreamHandler, "/ws/skill/stream")
                .setAllowedOrigins("*");
    }
}
