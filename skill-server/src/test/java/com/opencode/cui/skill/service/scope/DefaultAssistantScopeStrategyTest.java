package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.SnowflakeIdGenerator;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.DefaultCloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfile;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultAssistantScopeStrategy 单元测试。
 * 覆盖 PRD AC §C 中"dispatcher 新 API 路由 → buildInvoke"链路、各 action 分支、缺失规则容错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultAssistantScopeStrategy")
class DefaultAssistantScopeStrategyTest {

    @Mock
    private CloudRequestProfileRegistry profileRegistry;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private DefaultAssistantRuleService ruleService;

    @Mock
    private CloudRequestStrategy defaultStrategy;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultAssistantScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DefaultAssistantScopeStrategy(
                profileRegistry, cloudEventTranslator, objectMapper, idGenerator, ruleService);
        lenient().when(defaultStrategy.getName()).thenReturn(DefaultCloudRequestStrategy.STRATEGY_NAME);
        lenient().when(defaultStrategy.build(any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());
        // 默认 profileRegistry 行为：返回 (assistant_square, defaultStrategy)
        lenient().when(profileRegistry.resolve(any()))
                .thenAnswer(inv -> new CloudRequestProfile("assistant_square", defaultStrategy));
    }

    private CloudRequestContext capturedContext() {
        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(defaultStrategy).build(captor.capture());
        return captor.getValue();
    }

    private DefaultAssistantRule ruleHit() {
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));
        return rule;
    }

    @Test
    @DisplayName("getScope() returns \"default_assistant\"")
    void getScope_returnsDefaultAssistant() {
        assertEquals("default_assistant", strategy.getScope());
        assertEquals(DefaultAssistantScopeStrategy.SCOPE, strategy.getScope());
    }

    @Test
    @DisplayName("requiresOnlineCheck() returns false")
    void requiresOnlineCheck_returnsFalse() {
        assertFalse(strategy.requiresOnlineCheck());
    }

    @Test
    @DisplayName("requiresSessionCreatedCallback() returns false")
    void requiresSessionCreatedCallback_returnsFalse() {
        assertFalse(strategy.requiresSessionCreatedCallback());
    }

    @Test
    @DisplayName("generateToolSessionId() returns Snowflake long-parseable string")
    void generateToolSessionId_returnsNumericString() {
        when(idGenerator.nextId()).thenReturn(1234567890123456789L);
        String id = strategy.generateToolSessionId();
        assertNotNull(id);
        assertDoesNotThrow(() -> Long.parseLong(id));
        assertEquals("1234567890123456789", id);
    }

    @Test
    @DisplayName("translateEvent() delegates to CloudEventTranslator")
    void translateEvent_delegatesToCloudEventTranslator() {
        JsonNode event = objectMapper.createObjectNode().put("type", "text.delta");
        StreamMessage expected = StreamMessage.builder().type("text.delta").build();
        when(cloudEventTranslator.translate(event, "ses-1")).thenReturn(expected);

        assertSame(expected, strategy.translateEvent(event, "ses-1"));
    }

    @Test
    @DisplayName("buildInvoke(chat): 用 rule.businessTag 调 profileRegistry.resolve；wire assistantScope=business, payload.cloudProfile=profile.name")
    void buildInvoke_chat_resolvesByBusinessTag_writesWireFields() throws Exception {
        ruleHit();

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"ts-1\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "user-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        String result = strategy.buildInvoke(command, null);

        assertNotNull(result);
        // profile 是按 businessTag 反查（不是 ak/info）
        verify(profileRegistry).resolve(eq("assistant_square"));
        CloudRequestContext ctx = capturedContext();
        JsonNode platform = (JsonNode) ctx.getExtParameters().get("platformExtParam");
        assertEquals("assistant_square", platform.path("bizRobotTag").asText());

        JsonNode root = objectMapper.readTree(result);
        assertEquals("invoke", root.path("type").asText());
        // wire 上 assistantScope="business" 让 GW 按业务路径处理
        assertEquals("business", root.path("assistantScope").asText());
        assertEquals("ACC_V", root.path("assistantAccount").asText());
        assertEquals("assistant_square", root.path("businessTag").asText());
        assertEquals(GatewayActions.CHAT, root.path("action").asText());
        assertEquals("AK_V", root.path("ak").asText());
        assertEquals("session-1", root.path("welinkSessionId").asText());
        // payload.cloudProfile == profile.name()
        assertEquals("assistant_square", root.path("payload").path("cloudProfile").asText());
        assertEquals("ts-1", root.path("payload").path("toolSessionId").asText());
    }

    @Test
    @DisplayName("buildInvoke(chat): 缺省 assistantAccount 时从 rule 注入；缺省 sendUserAccount 时回退到 command.userId()")
    void buildInvoke_chat_injectsAssistantAccountAndSendUserAccount() {
        ruleHit();

        // payload 不带 assistantAccount / sendUserAccount —— 触发 strategy 内部从 rule + command.userId() 注入
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"ts-1\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "cookieUser-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        strategy.buildInvoke(command, null);

        CloudRequestContext ctx = capturedContext();
        assertEquals("ACC_V", ctx.getAssistantAccount());
        assertEquals("cookieUser-1", ctx.getSendUserAccount());
    }

    @Test
    @DisplayName("buildInvoke(chat): payload 已含 assistantAccount/sendUserAccount → 优先用 payload 值（不被 rule 覆盖）")
    void buildInvoke_chat_payloadOverridesRule() {
        ruleHit();

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"ts-1\","
                + "\"assistantAccount\":\"asst-payload\",\"sendUserAccount\":\"u-payload\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "user-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        strategy.buildInvoke(command, null);

        CloudRequestContext ctx = capturedContext();
        assertEquals("asst-payload", ctx.getAssistantAccount());
        assertEquals("u-payload", ctx.getSendUserAccount());
    }

    @Test
    @DisplayName("buildInvoke(question_reply): 写 action=question_reply，payload 含 assistantAccount/sendUserAccount，reply 字段透传")
    void buildInvoke_questionReply_writesActionAndReplyFields() throws Exception {
        ruleHit();

        String payload = "{\"toolSessionId\":\"ts-1\",\"toolCallId\":\"call-q\",\"answer\":\"[[\\\"A\\\"]]\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "u-1", "session-1", GatewayActions.QUESTION_REPLY,
                payload, null, "helpdesk", "direct");

        String result = strategy.buildInvoke(command, null);

        assertNotNull(result);
        JsonNode root = objectMapper.readTree(result);
        assertEquals(GatewayActions.QUESTION_REPLY, root.path("action").asText());

        CloudRequestContext ctx = capturedContext();
        // assistantAccount 注入自 rule（payload 没传）
        assertEquals("ACC_V", ctx.getAssistantAccount());
        // sendUserAccount 回退到 command.userId（payload 没传）
        assertEquals("u-1", ctx.getSendUserAccount());
        // reply 字段透传
        assertEquals("call-q", ctx.getReplyToolCallId());
        assertNotNull(ctx.getReplyAnswers());
        assertThat(ctx.getReplyAnswers()).containsExactly(java.util.List.of("A"));
    }

    @Test
    @DisplayName("buildInvoke(permission_reply): 写 action=permission_reply，reply 字段透传")
    void buildInvoke_permissionReply_writesActionAndReplyFields() throws Exception {
        ruleHit();

        String payload = "{\"toolSessionId\":\"ts-1\",\"permissionId\":\"perm-1\",\"response\":\"once\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "u-1", "session-1", GatewayActions.PERMISSION_REPLY,
                payload, null, "helpdesk", "direct");

        String result = strategy.buildInvoke(command, null);

        assertNotNull(result);
        JsonNode root = objectMapper.readTree(result);
        assertEquals(GatewayActions.PERMISSION_REPLY, root.path("action").asText());

        CloudRequestContext ctx = capturedContext();
        assertEquals("ACC_V", ctx.getAssistantAccount());
        assertEquals("u-1", ctx.getSendUserAccount());
        assertEquals("perm-1", ctx.getReplyPermissionId());
        assertEquals("once", ctx.getReplyResponse());
    }

    @Test
    @DisplayName("buildInvoke(chat): cloudRequest 来自 strategy.build；不写 replyContext")
    void buildInvoke_chat_doesNotWriteReplyFields() throws Exception {
        ruleHit();
        ObjectNode emptyCr = objectMapper.createObjectNode();
        when(defaultStrategy.build(any(CloudRequestContext.class))).thenReturn(emptyCr);

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"ts-1\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "u-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        String result = strategy.buildInvoke(command, null);

        JsonNode cr = objectMapper.readTree(result).path("payload").path("cloudRequest");
        assertFalse(cr.has("replyContext"));

        CloudRequestContext ctx = capturedContext();
        assertNull(ctx.getReplyToolCallId());
        assertNull(ctx.getReplyAnswers());
        assertNull(ctx.getReplyPermissionId());
        assertNull(ctx.getReplyResponse());
    }

    @Test
    @DisplayName("buildInvoke(abort_session): 不构造 cloudRequest，也不需要 sendUserAccount")
    void buildInvoke_abortSession_sendsControlInvokeWithoutCloudRequest() throws Exception {
        ruleHit();

        String payload = "{\"toolSessionId\":\"ts-1\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "u-1", "session-1", GatewayActions.ABORT_SESSION,
                payload, null, "helpdesk", "direct");

        String result = strategy.buildInvoke(command, null);

        assertNotNull(result);
        verify(defaultStrategy, never()).build(any(CloudRequestContext.class));
        JsonNode root = objectMapper.readTree(result);
        assertEquals(GatewayActions.ABORT_SESSION, root.path("action").asText());
        assertEquals("business", root.path("assistantScope").asText());
        assertEquals("ACC_V", root.path("assistantAccount").asText());
        assertEquals("assistant_square", root.path("businessTag").asText());
        assertEquals("session-1", root.path("welinkSessionId").asText());
        assertEquals("ts-1", root.path("payload").path("toolSessionId").asText());
        assertEquals("assistant_square", root.path("payload").path("cloudProfile").asText());
        assertFalse(root.path("payload").has("cloudRequest"));
    }

    @Test
    @DisplayName("buildInvoke: rule.lookup 返 empty（运行时规则被删了） → 抛 IAE，不静默")
    void buildInvoke_ruleMissAtRuntime_throwsIAE() {
        // dispatcher 先用旧规则把 strategy 选定，buildInvoke 调用前 sys_config 被删 → strategy 必须显式失败
        when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.empty());

        String payload = "{\"text\":\"hi\"}";
        InvokeCommand command = new InvokeCommand(
                "AK_V", "u-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> strategy.buildInvoke(command, null));
        assertTrue(ex.getMessage().contains("rule not found"));
        assertTrue(ex.getMessage().contains("helpdesk"));
        assertTrue(ex.getMessage().contains("direct"));
    }

    @Test
    @DisplayName("buildInvoke: rule lookup 用 command.domain()/domainType()，不用 command.ak()")
    void buildInvoke_lookupUsesDomainNotAk() {
        ruleHit();

        // 故意把 ak 和 (domain, type) 关系打散，验证查询用 domain/type 不用 ak
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"ts-1\"}";
        InvokeCommand command = new InvokeCommand(
                "RANDOM_AK", "u-1", "session-1", GatewayActions.CHAT,
                payload, null, "helpdesk", "direct");

        strategy.buildInvoke(command, null);

        verify(ruleService).lookup("helpdesk", "direct");
    }
}
