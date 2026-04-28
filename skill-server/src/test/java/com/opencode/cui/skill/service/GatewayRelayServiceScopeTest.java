package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GatewayRelayService scope 分支测试：验证 business/personal invoke 构建逻辑。
 */
@ExtendWith(MockitoExtension.class)
class GatewayRelayServiceScopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GatewayMessageRouter messageRouter;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private AssistantIdResolverService assistantIdResolverService;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantScopeStrategy businessStrategy;
    @Mock
    private AssistantScopeStrategy personalStrategy;
    @Mock
    private GatewayRelayService.GatewayRelayTarget gatewayRelayTarget;
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;

    private GatewayRelayService service;

    @BeforeEach
    void setUp() {
        service = new GatewayRelayService(
                objectMapper,
                messageRouter,
                rebuildService,
                redisMessageBroker,
                assistantIdResolverService,
                assistantInfoService,
                scopeDispatcher,
                emitter);
        service.setGatewayRelayTarget(gatewayRelayTarget);

        lenient().when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        lenient().when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
        // business strategy scope label
        lenient().when(businessStrategy.getScope()).thenReturn("business");
        // personal strategy scope label (used as fallback when info is null)
        lenient().when(personalStrategy.getScope()).thenReturn("personal");
        // default: getStrategy(null/personal info) → personalStrategy
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalStrategy);
    }

    @Test
    @DisplayName("S54: business invoke contains assistantScope=business and cloudRequest payload")
    void s54_businessInvokeContainsAssistantScopeAndCloudRequest() throws Exception {
        // arrange: business assistant info
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");
        when(assistantInfoService.getAssistantInfo("ak-biz")).thenReturn(info);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);

        // strategy.buildInvoke returns a JSON with assistantScope=business and cloudRequest payload
        String strategyResult = "{\"type\":\"invoke\",\"ak\":\"ak-biz\",\"source\":\"skill-server\""
                + ",\"action\":\"chat\",\"assistantScope\":\"business\""
                + ",\"payload\":{\"type\":\"text\",\"content\":\"hello\",\"topicId\":\"cloud-abc123\"}}";
        when(businessStrategy.buildInvoke(any(), eq(info))).thenReturn(strategyResult);

        InvokeCommand command = new InvokeCommand("ak-biz", "user-1", "ses-1", "chat",
                "{\"text\":\"hello\",\"toolSessionId\":\"cloud-abc123\"}");

        // act
        service.sendInvokeToGateway(command);

        // assert: captured message sent to gateway
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget).sendToGateway(captor.capture());

        JsonNode sent = objectMapper.readTree(captor.getValue());
        assertEquals("business", sent.path("assistantScope").asText());
        assertNotNull(sent.get("payload"), "cloudRequest payload should be present");
        assertEquals("text", sent.path("payload").path("type").asText());
    }

    @Test
    @DisplayName("S55: personal invoke uses buildInvokeMessage (default path)")
    void s55_personalInvokeUsesBuildInvokeMessage() throws Exception {
        // arrange: personal assistant (info is null -> falls to else branch)
        when(assistantInfoService.getAssistantInfo("ak-personal")).thenReturn(null);

        InvokeCommand command = new InvokeCommand("ak-personal", "user-1", "ses-1", "chat",
                "{\"text\":\"hello\",\"toolSessionId\":\"tool-001\"}");

        // act
        service.sendInvokeToGateway(command);

        // assert: default buildInvokeMessage path produces standard invoke format
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget).sendToGateway(captor.capture());

        JsonNode sent = objectMapper.readTree(captor.getValue());
        assertEquals("invoke", sent.path("type").asText());
        assertEquals("ak-personal", sent.path("ak").asText());
        assertEquals("chat", sent.path("action").asText());
        assertEquals("skill-server", sent.path("source").asText());
        // personal path should NOT have assistantScope
        assertTrue(sent.path("assistantScope").isMissingNode(),
                "personal invoke should not have assistantScope field");
    }

    @Test
    @DisplayName("S56: business invoke cloudRequest contains correct topicId = toolSessionId")
    void s56_businessInvokeCloudRequestTopicIdEqualsToolSessionId() throws Exception {
        // arrange
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");
        when(assistantInfoService.getAssistantInfo("ak-biz")).thenReturn(info);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);

        String strategyResult = "{\"type\":\"invoke\",\"ak\":\"ak-biz\",\"source\":\"skill-server\""
                + ",\"action\":\"chat\",\"assistantScope\":\"business\""
                + ",\"payload\":{\"type\":\"text\",\"content\":\"hello\",\"topicId\":\"cloud-topic-42\"}}";
        when(businessStrategy.buildInvoke(any(), eq(info))).thenReturn(strategyResult);

        InvokeCommand command = new InvokeCommand("ak-biz", "user-1", "ses-1", "chat",
                "{\"text\":\"hello\",\"toolSessionId\":\"cloud-topic-42\"}");

        // act
        service.sendInvokeToGateway(command);

        // assert: verify the strategy received the command with toolSessionId in payload
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(businessStrategy).buildInvoke(cmdCaptor.capture(), eq(info));

        JsonNode payloadNode = objectMapper.readTree(cmdCaptor.getValue().payload());
        assertEquals("cloud-topic-42", payloadNode.path("toolSessionId").asText());

        // verify sent message has correct topicId in cloudRequest
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());
        JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
        assertEquals("cloud-topic-42", sent.path("payload").path("topicId").asText());
    }
}
