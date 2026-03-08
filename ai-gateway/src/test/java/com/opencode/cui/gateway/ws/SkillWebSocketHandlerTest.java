package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.service.SkillRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillWebSocketHandlerTest {

    @Mock
    private SkillRelayService skillRelayService;

    @Mock
    private WebSocketSession session;

    private TestSkillWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestSkillWebSocketHandler(new ObjectMapper(), skillRelayService, "secret-token");
    }

    @Test
    @DisplayName("valid internal token registers skill session")
    void validTokenRegistersSkillSession() throws Exception {
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/internal/skill?token=secret-token"));

        handler.onOpen(session);

        verify(skillRelayService).registerSkillSession(session);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("invalid internal token rejects connection")
    void invalidTokenRejectsConnection() throws Exception {
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/internal/skill?token=wrong"));

        handler.onOpen(session);

        verify(skillRelayService, never()).registerSkillSession(session);
        verify(session).close(argThat(status -> status != null
                && CloseStatus.NOT_ACCEPTABLE.getCode() == status.getCode()
                && "Invalid internal token".equals(status.getReason())));
    }

    @Test
    @DisplayName("invoke message delegates to skill relay service")
    void invokeDelegatesToSkillRelayService() throws Exception {
        handler.handle(session, "{\"type\":\"invoke\",\"agentId\":\"agent-1\",\"sessionId\":\"42\",\"action\":\"chat\"}");

        verify(skillRelayService).handleInvokeFromSkill(eq(session),
                argThat(message -> "invoke".equals(message.getType())
                        && "agent-1".equals(message.getAgentId())
                        && "42".equals(message.getSessionId())));
    }

    @Test
    @DisplayName("non invoke message is ignored")
    void nonInvokeMessageIsIgnored() throws Exception {
        handler.handle(session, "{\"type\":\"tool_event\",\"sessionId\":\"42\"}");

        verifyNoInteractions(skillRelayService);
    }

    @Test
    @DisplayName("malformed JSON is ignored")
    void malformedJsonIsIgnored() throws Exception {
        handler.handle(session, "not-json");

        verifyNoInteractions(skillRelayService);
    }

    private static final class TestSkillWebSocketHandler extends SkillWebSocketHandler {

        private TestSkillWebSocketHandler(ObjectMapper objectMapper,
                SkillRelayService skillRelayService,
                String internalToken) {
            super(objectMapper, skillRelayService, internalToken);
        }

        private void onOpen(WebSocketSession session) throws Exception {
            super.afterConnectionEstablished(session);
        }

        private void handle(WebSocketSession session, String payload) throws Exception {
            super.handleTextMessage(session, new TextMessage(payload));
        }
    }
}
