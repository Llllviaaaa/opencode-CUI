package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CloudAgentService 单元测试（TDD）。
 *
 * <ul>
 *   <li>正常流程：获取路由信息、构建上下文、连接云端、注入路由上下文后转发</li>
 *   <li>路由信息获取失败：返回 tool_error</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudAgentServiceTest {

    @Mock
    private CloudRouteService cloudRouteService;
    @Mock
    private CloudProtocolClient cloudProtocolClient;
    @Mock
    private SkillRelayService skillRelayService;

    @Captor
    private ArgumentCaptor<Consumer<GatewayMessage>> onEventCaptor;
    @Captor
    private ArgumentCaptor<Consumer<Throwable>> onErrorCaptor;
    @Captor
    private ArgumentCaptor<CloudConnectionContext> contextCaptor;
    @Captor
    private ArgumentCaptor<GatewayMessage> messageCaptor;

    private CloudAgentService cloudAgentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cloudAgentService = new CloudAgentService(cloudRouteService, cloudProtocolClient, skillRelayService);
    }

    private GatewayMessage buildInvokeMessage() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", objectMapper.createObjectNode().put("prompt", "hello"));
        payload.put("toolSessionId", "tool-session-001");

        return GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .ak("ak-test-001")
                .userId("user-001")
                .welinkSessionId("welink-session-001")
                .traceId("trace-001")
                .payload(payload)
                .build();
    }

    private CloudRouteInfo buildRouteInfo() {
        CloudRouteInfo info = new CloudRouteInfo();
        info.setAppId("app_36209");
        info.setEndpoint("https://cloud.example.com/chat");
        info.setProtocol("sse");
        info.setAuthType("soa");
        return info;
    }

    @Nested
    @DisplayName("正常流程")
    class HappyPathTests {

        @Test
        @DisplayName("获取路由信息并构建正确的 CloudConnectionContext")
        void shouldBuildCorrectConnectionContext() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            CloudRouteInfo routeInfo = buildRouteInfo();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(routeInfo);

            cloudAgentService.handleInvoke(invokeMsg);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(),
                    any()
            );

            CloudConnectionContext ctx = contextCaptor.getValue();
            assertEquals("https://cloud.example.com/chat", ctx.getEndpoint());
            assertEquals("app_36209", ctx.getAppId());
            assertEquals("soa", ctx.getAuthType());
            assertEquals("trace-001", ctx.getTraceId());
            assertNotNull(ctx.getCloudRequest());
        }

        @Test
        @DisplayName("云端事件注入路由上下文后转发到 SkillRelayService")
        void shouldInjectRoutingContextAndRelayEvents() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            CloudRouteInfo routeInfo = buildRouteInfo();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(routeInfo);

            cloudAgentService.handleInvoke(invokeMsg);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    onEventCaptor.capture(),
                    any()
            );

            // Simulate cloud event
            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(skillRelayService).relayToSkill(messageCaptor.capture());
            GatewayMessage relayed = messageCaptor.getValue();

            assertEquals("ak-test-001", relayed.getAk());
            assertEquals("user-001", relayed.getUserId());
            assertEquals("welink-session-001", relayed.getWelinkSessionId());
            assertEquals("trace-001", relayed.getTraceId());
            assertEquals("tool-session-001", relayed.getToolSessionId());
        }

        @Test
        @DisplayName("云端事件已有 toolSessionId 时不覆盖")
        void shouldNotOverrideExistingToolSessionId() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            CloudRouteInfo routeInfo = buildRouteInfo();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(routeInfo);

            cloudAgentService.handleInvoke(invokeMsg);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    onEventCaptor.capture(),
                    any()
            );

            // Simulate cloud event with existing toolSessionId
            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("cloud-tool-session-999")
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(skillRelayService).relayToSkill(messageCaptor.capture());
            GatewayMessage relayed = messageCaptor.getValue();
            assertEquals("cloud-tool-session-999", relayed.getToolSessionId());
        }
    }

    @Nested
    @DisplayName("错误场景")
    class ErrorTests {

        @Test
        @DisplayName("路由信息获取失败时返回 tool_error")
        void shouldReturnToolErrorWhenRouteInfoFails() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(null);

            cloudAgentService.handleInvoke(invokeMsg);

            verify(cloudProtocolClient, never()).connect(any(), any(), any(), any());
            verify(skillRelayService).relayToSkill(messageCaptor.capture());

            GatewayMessage errorMsg = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, errorMsg.getType());
            assertEquals("ak-test-001", errorMsg.getAk());
            assertEquals("user-001", errorMsg.getUserId());
            assertEquals("welink-session-001", errorMsg.getWelinkSessionId());
            assertEquals("tool-session-001", errorMsg.getToolSessionId());
            assertNotNull(errorMsg.getError());
        }

        @Test
        @DisplayName("云端连接异常时 onError 构建 tool_error 转发")
        void shouldRelayToolErrorOnCloudConnectionFailure() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            CloudRouteInfo routeInfo = buildRouteInfo();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(routeInfo);

            cloudAgentService.handleInvoke(invokeMsg);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(),
                    onErrorCaptor.capture()
            );

            // Simulate error
            onErrorCaptor.getValue().accept(new RuntimeException("Connection timeout"));

            verify(skillRelayService).relayToSkill(messageCaptor.capture());
            GatewayMessage errorMsg = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, errorMsg.getType());
            assertEquals("ak-test-001", errorMsg.getAk());
            assertTrue(errorMsg.getError().contains("Connection timeout"));
        }
    }
}
