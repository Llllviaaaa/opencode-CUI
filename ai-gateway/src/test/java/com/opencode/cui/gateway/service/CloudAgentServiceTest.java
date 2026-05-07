package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import com.opencode.cui.gateway.service.cloud.WebHookExecutor;
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
import static org.mockito.Mockito.lenient;

/**
 * CloudAgentService 单元测试（TDD）。
 *
 * <p>覆盖 callback 配置驱动的分叉逻辑：
 * <ul>
 *   <li>action → scope 映射（chat / question_reply / permission_reply / unknown）</li>
 *   <li>channelType vs action 双向校验</li>
 *   <li>SSE/WebSocket 走 CloudProtocolClient；webhook 走 WebHookExecutor</li>
 *   <li>v1 模式 q_r/p_r 因 LegacyRouteResolver 仅 chat scope 接通而返回 null</li>
 *   <li>原 chat 路径的 fallback messageId / fallback partId / errorSent 单次回流逻辑保留</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudAgentServiceTest {

    private static final String TEST_AK = "ak-test-001";
    private static final String CHAT_SCOPE = "callback:weagent:chat";
    private static final String QR_SCOPE = "callback:weagent:question_reply";
    private static final String PR_SCOPE = "callback:weagent:permission_reply";

    @Mock
    private CallbackConfigService callbackConfigService;
    @Mock
    private CloudProtocolClient cloudProtocolClient;
    @Mock
    private WebHookExecutor webHookExecutor;
    @Mock
    private CloudTimeoutProperties cloudTimeoutProperties;
    @Mock
    private Consumer<GatewayMessage> onRelay;

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
        lenient().when(cloudTimeoutProperties.getFirstEventTimeoutSeconds()).thenReturn(120);
        lenient().when(cloudTimeoutProperties.getEffectiveIdleTimeoutSeconds(anyString())).thenReturn(90);
        lenient().when(cloudTimeoutProperties.getMaxDurationSeconds()).thenReturn(600);

        cloudAgentService = new CloudAgentService(
                callbackConfigService, cloudProtocolClient, webHookExecutor, cloudTimeoutProperties);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private GatewayMessage buildInvoke(String action, String ak) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", objectMapper.createObjectNode().put("prompt", "hello"));
        payload.put("toolSessionId", "tool-session-001");

        return GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .ak(ak)
                .action(action)
                .userId("user-001")
                .welinkSessionId("welink-session-001")
                .traceId("trace-001")
                .payload(payload)
                .build();
    }

    private CallbackConfig buildCfg(String channelType, String channelAddress, String authType, String appId) {
        CallbackConfig c = new CallbackConfig();
        c.setAk(TEST_AK);
        c.setScope("callback:weagent:chat");
        c.setChannelType(channelType);
        c.setChannelAddress(channelAddress);
        c.setAuthType(authType);
        c.setAppId(appId);
        return c;
    }

    // ---------------------------------------------------------------------
    // action → scope 路由
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Action 路由分叉")
    class ActionRoutingTests {

        @Test
        @DisplayName("chat action → 走 CloudProtocolClient (SSE)")
        void handleInvoke_chatAction_routesToSseProtocol() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("chat action → 走 CloudProtocolClient (WebSocket)")
        void handleInvoke_chatAction_routesToWebSocketProtocol() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("websocket", "wss://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(eq("websocket"), any(), any(), any(), any());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("question_reply action → 走 WebHookExecutor")
        void handleInvoke_questionReplyAction_routesToWebHook() {
            when(callbackConfigService.getConfig(TEST_AK, QR_SCOPE))
                    .thenReturn(buildCfg("webhook", "https://cloud.example.com/qr", "soa", null));

            GatewayMessage invoke = buildInvoke("question_reply", TEST_AK);
            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(webHookExecutor).execute(any(CloudConnectionContext.class),
                    eq(onRelay), eq(invoke), eq("tool-session-001"));
            verifyNoInteractions(cloudProtocolClient);
        }

        @Test
        @DisplayName("permission_reply action → 走 WebHookExecutor")
        void handleInvoke_permissionReplyAction_routesToWebHook() {
            when(callbackConfigService.getConfig(TEST_AK, PR_SCOPE))
                    .thenReturn(buildCfg("webhook", "https://cloud.example.com/pr", "soa", null));

            GatewayMessage invoke = buildInvoke("permission_reply", TEST_AK);
            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(webHookExecutor).execute(any(CloudConnectionContext.class),
                    eq(onRelay), eq(invoke), eq("tool-session-001"));
            verifyNoInteractions(cloudProtocolClient);
        }

        @Test
        @DisplayName("未知 action → 回流 tool_error")
        void handleInvoke_unknownAction_emitsToolError() {
            cloudAgentService.handleInvoke(buildInvoke("unknown_action", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Unknown action"),
                    "Expected 'Unknown action' in error: " + err.getError());
            assertNull(err.getReason(),
                    "Unknown action should not carry a structured reason; got: " + err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // channelType vs action 校验
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("ChannelType 校验")
    class ChannelTypeValidationTests {

        @Test
        @DisplayName("chat 收到 webhook channel → tool_error")
        void handleInvoke_chatWithWebhookChannel_emitsToolError() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("webhook", "https://x", "soa", null));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for chat"),
                    "Expected 'Invalid channel type for chat' in error: " + err.getError());
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("question_reply 收到 sse channel → tool_error")
        void handleInvoke_questionReplyWithSseChannel_emitsToolError() {
            when(callbackConfigService.getConfig(TEST_AK, QR_SCOPE))
                    .thenReturn(buildCfg("sse", "https://x", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("question_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for reply"),
                    "Expected 'Invalid channel type for reply' in error: " + err.getError());
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("permission_reply 收到 websocket channel → tool_error")
        void handleInvoke_permissionReplyWithWebSocketChannel_emitsToolError() {
            when(callbackConfigService.getConfig(TEST_AK, PR_SCOPE))
                    .thenReturn(buildCfg("websocket", "wss://x", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("permission_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for reply"));
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // 配置缺失（含 v1 模式 q_r/p_r 不接通）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("配置缺失")
    class MissingConfigTests {

        @Test
        @DisplayName("chat 配置缺失 → tool_error，错误消息保留 'Cloud route info not found'")
        void handleInvoke_chatConfigMissing_emitsLegacyErrorMessage() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE)).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient, never()).connect(any(), any(), any(), any(), any());
            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertEquals(TEST_AK, err.getAk());
            assertEquals("user-001", err.getUserId());
            assertEquals("welink-session-001", err.getWelinkSessionId());
            assertEquals("tool-session-001", err.getToolSessionId());
            assertNotNull(err.getError());
            assertTrue(err.getError().contains("Cloud route info not found for ak: " + TEST_AK),
                    "Expected v1-compatible error message, got: " + err.getError());
            assertEquals("callback_config_missing", err.getReason(),
                    "callback config missing should carry structured reason for SS routing");
        }

        @Test
        @DisplayName("v1 模式 question_reply 配置缺失 → tool_error 含 'not enabled'")
        void handleInvoke_v1ModeQuestionReplyReturnsNullConfig_emitsToolError() {
            when(callbackConfigService.getConfig(TEST_AK, QR_SCOPE)).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("question_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("question_reply"));
            assertTrue(err.getError().contains("not enabled"),
                    "Expected 'not enabled' in error: " + err.getError());
            assertEquals("callback_config_missing", err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("v1 模式 permission_reply 配置缺失 → tool_error 含 'not enabled'")
        void handleInvoke_v1ModePermissionReplyReturnsNullConfig_emitsToolError() {
            when(callbackConfigService.getConfig(TEST_AK, PR_SCOPE)).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("permission_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("permission_reply"));
            assertTrue(err.getError().contains("not enabled"));
            assertEquals("callback_config_missing", err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // chat 路径正常流程（保留原有 fallback 与 errorSent 行为）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("chat 路径正常流程")
    class ChatHappyPathTests {

        @Test
        @DisplayName("构建正确的 CloudConnectionContext (含 channelType / scope)")
        void shouldBuildCorrectConnectionContext() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app_36209"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    any()
            );

            CloudConnectionContext ctx = contextCaptor.getValue();
            assertEquals("https://cloud.example.com/chat", ctx.getChannelAddress());
            assertEquals("sse", ctx.getChannelType());
            assertEquals(CHAT_SCOPE, ctx.getScope());
            assertEquals("app_36209", ctx.getAppId());
            assertEquals("soa", ctx.getAuthType());
            assertEquals("trace-001", ctx.getTraceId());
            assertNotNull(ctx.getCloudRequest());
        }

        @Test
        @DisplayName("云端事件注入路由上下文后通过 onRelay 转发")
        void shouldInjectRoutingContextAndRelayEvents() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    onEventCaptor.capture(),
                    any()
            );

            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage relayed = messageCaptor.getValue();

            assertEquals(TEST_AK, relayed.getAk());
            assertEquals("user-001", relayed.getUserId());
            assertEquals("welink-session-001", relayed.getWelinkSessionId());
            assertEquals("trace-001", relayed.getTraceId());
            assertEquals("tool-session-001", relayed.getToolSessionId());
        }

        @Test
        @DisplayName("云端事件已有 toolSessionId 时不覆盖")
        void shouldNotOverrideExistingToolSessionId() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    onEventCaptor.capture(),
                    any()
            );

            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("cloud-tool-session-999")
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(onRelay).accept(messageCaptor.capture());
            assertEquals("cloud-tool-session-999", messageCaptor.getValue().getToolSessionId());
        }
    }

    // ---------------------------------------------------------------------
    // chat 路径异常流程（保留原有 onError / 超时行为）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("chat 路径异常流程")
    class ChatErrorTests {

        @Test
        @DisplayName("云端连接异常时 onError 通过 onRelay 构建 tool_error 转发")
        void shouldRelayToolErrorOnCloudConnectionFailure() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    onErrorCaptor.capture()
            );

            onErrorCaptor.getValue().accept(new RuntimeException("Connection timeout"));

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertEquals(TEST_AK, err.getAk());
            assertTrue(err.getError().contains("Connection timeout"));
            assertNull(err.getReason());
        }

        @Test
        @DisplayName("云端超时时通过 onRelay 返回包含超时信息的 tool_error")
        void shouldRelayToolErrorWithTimeoutInfoOnTimeout() {
            when(callbackConfigService.getConfig(TEST_AK, CHAT_SCOPE))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    onErrorCaptor.capture()
            );

            onErrorCaptor.getValue().accept(
                    new RuntimeException("Cloud agent error: idle_timeout (elapsed: 90s)"));

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("idle_timeout"));
            assertNull(err.getReason());
        }
    }
}
