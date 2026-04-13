package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundProcessingServiceTest {

    @Mock
    private AssistantAccountResolverService resolverService;
    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private ImSessionManager sessionManager;
    @Mock
    private ContextInjectionService contextInjectionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private SkillMessageService messageService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private OutboundDeliveryDispatcher outboundDeliveryDispatcher;

    private AssistantIdProperties assistantIdProperties;
    private InboundProcessingService service;

    @BeforeEach
    void setUp() {
        assistantIdProperties = new AssistantIdProperties();
        assistantIdProperties.setEnabled(true);
        assistantIdProperties.setTargetToolType("assistant");

        service = new InboundProcessingService(
                resolverService,
                assistantIdProperties,
                gatewayApiClient,
                sessionManager,
                contextInjectionService,
                gatewayRelayService,
                messageService,
                rebuildService,
                new ObjectMapper(),
                assistantInfoService,
                scopeDispatcher,
                outboundDeliveryDispatcher);

        // 默认 scope 策略：personal（requiresOnlineCheck=true）
        AssistantScopeStrategy personalStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(personalStrategy);
        lenient().when(assistantInfoService.getCachedScope(any())).thenReturn("personal");
        // 默认 Agent 在线
        lenient().when(gatewayApiClient.getAgentByAk(any()))
                .thenReturn(AgentSummary.builder().ak("ak-001").toolType("assistant").build());
    }

    // ==================== processChat ====================

    @Test
    @DisplayName("processChat: session ready → sends CHAT invoke")
    void processChatSessionReady() {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "hello", "text", null, null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("ak-001", captor.getValue().ak());
        assertEquals("owner-001", captor.getValue().userId());
        assertEquals(GatewayActions.CHAT, captor.getValue().action());
        assertTrue(captor.getValue().payload().contains("tool-001"));
        verify(messageService).saveUserMessage(101L, "hello");
        verify(rebuildService).appendPendingMessage("101", "hello");
    }

    @Test
    @DisplayName("processChat: invalid assistant → returns error(404)")
    void processChatInvalidAssistant() {
        when(resolverService.resolve("unknown")).thenReturn(null);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "unknown",
                "hello", "text", null, null);

        assertFalse(result.success());
        assertEquals(404, result.code());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: session not found → calls createSessionAsync")
    void processChatNoSession() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);
        when(contextInjectionService.resolvePrompt("direct", "first msg", null))
                .thenReturn("first msg");

        InboundResult result = service.processChat(
                "im", "direct", "dm-new", "assist-001",
                "first msg", "text", null, null);

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", "first msg");
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    // ==================== processQuestionReply ====================

    @Test
    @DisplayName("processQuestionReply: session ready → sends QUESTION_REPLY invoke")
    void processQuestionReplySessionReady() {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "yes", "tc-001", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals(GatewayActions.QUESTION_REPLY, captor.getValue().action());
        assertTrue(captor.getValue().payload().contains("yes"));
        assertTrue(captor.getValue().payload().contains("tc-001"));
        assertTrue(captor.getValue().payload().contains("tool-001"));
    }

    // ==================== processPermissionReply ====================

    @Test
    @DisplayName("processPermissionReply: session ready → sends PERMISSION_REPLY invoke + broadcasts")
    void processPermissionReplySessionReady() {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "perm-001", "allow", null);

        assertTrue(result.success());
        // 验证 invoke
        ArgumentCaptor<InvokeCommand> invokeCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(invokeCaptor.capture());
        assertEquals(GatewayActions.PERMISSION_REPLY, invokeCaptor.getValue().action());
        assertTrue(invokeCaptor.getValue().payload().contains("perm-001"));
        assertTrue(invokeCaptor.getValue().payload().contains("allow"));

        // 验证广播
        ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(gatewayRelayService).publishProtocolMessage(eq("101"), msgCaptor.capture());
        assertEquals(StreamMessage.Types.PERMISSION_REPLY, msgCaptor.getValue().getType());
        assertEquals("perm-001", msgCaptor.getValue().getPermission().getPermissionId());
        assertEquals("allow", msgCaptor.getValue().getPermission().getResponse());
    }

    // ==================== processRebuild ====================

    @Test
    @DisplayName("processRebuild: session exists → calls requestToolSession")
    void processRebuildSessionExists() {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001");

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, null);
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processRebuild: session not found → calls createSessionAsync")
    void processRebuildNoSession() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-new", "assist-001");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", null);
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    // ==================== helper ====================

    private SkillSession buildReadySession() {
        SkillSession session = new SkillSession();
        session.setId(101L);
        session.setAk("ak-001");
        session.setUserId("owner-001");
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("direct");
        session.setToolSessionId("tool-001");
        return session;
    }
}
