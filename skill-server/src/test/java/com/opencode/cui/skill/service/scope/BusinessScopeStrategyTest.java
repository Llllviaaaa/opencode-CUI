package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessScopeStrategy")
class BusinessScopeStrategyTest {

    @Mock
    private CloudRequestProfileRegistry profileRegistry;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private CloudRequestStrategy defaultStrategy;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BusinessScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new BusinessScopeStrategy(profileRegistry, cloudEventTranslator, objectMapper, idGenerator);
        lenient().when(defaultStrategy.getName()).thenReturn(DefaultCloudRequestStrategy.STRATEGY_NAME);
        lenient().when(defaultStrategy.build(any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());
        lenient().when(profileRegistry.resolve(any()))
                .thenAnswer(inv -> new CloudRequestProfile(DefaultCloudRequestStrategy.STRATEGY_NAME, defaultStrategy));
    }

    /** Helper to capture the CloudRequestContext passed to the strategy. */
    private CloudRequestContext capturedContext() {
        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(defaultStrategy).build(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("getScope() returns \"business\"")
    void getScope_returnsBusiness() {
        assertEquals("business", strategy.getScope());
    }

    @Test
    @DisplayName("generateToolSessionId() returns numeric string (Long-parsable, Snowflake)")
    void generateToolSessionId_returnsNumericString() {
        when(idGenerator.nextId()).thenReturn(1234567890123456789L);
        String id = strategy.generateToolSessionId();
        assertNotNull(id);
        // Snowflake long ID 形式：纯数字字符串，Long.parseLong 必须成功
        assertDoesNotThrow(() -> Long.parseLong(id));
        assertEquals("1234567890123456789", id);
    }

    @Test
    @DisplayName("generateToolSessionId() returns unique values (different snowflake calls)")
    void generateToolSessionId_returnsUniqueValues() {
        when(idGenerator.nextId()).thenReturn(1L, 2L);
        String id1 = strategy.generateToolSessionId();
        String id2 = strategy.generateToolSessionId();
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("requiresSessionCreatedCallback() returns false")
    void requiresSessionCreatedCallback_returnsFalse() {
        assertFalse(strategy.requiresSessionCreatedCallback());
    }

    @Test
    @DisplayName("requiresOnlineCheck() returns false")
    void requiresOnlineCheck_returnsFalse() {
        assertFalse(strategy.requiresOnlineCheck());
    }

    @Test
    @DisplayName("translateEvent() delegates to CloudEventTranslator")
    void translateEvent_delegatesToCloudEventTranslator() {
        JsonNode event = objectMapper.createObjectNode().put("type", "text.delta");
        String sessionId = "session-123";
        StreamMessage expected = StreamMessage.builder().type("text.delta").build();
        when(cloudEventTranslator.translate(event, sessionId)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, sessionId);

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, sessionId);
    }

    @Test
    @DisplayName("buildInvoke() resolves profile via registry and includes cloudProfile in payload")
    void buildInvoke_resolvesProfileAndIncludesCloudProfileInPayload() throws Exception {
        // profile name 来自 registry，cloudProfile 字段下沉到 payload
        when(profileRegistry.resolve(eq("app-123")))
                .thenReturn(new CloudRequestProfile("assistant_square", defaultStrategy));

        InvokeCommand command = new InvokeCommand("ak-1", "user-1", "session-1", "chat", "{\"content\":\"hello\"}");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-123");

        String result = strategy.buildInvoke(command, info);

        assertNotNull(result);
        verify(profileRegistry).resolve(eq("app-123"));
        verify(defaultStrategy).build(any(CloudRequestContext.class));
        JsonNode root = objectMapper.readTree(result);
        assertThat(root.path("businessTag").asText()).isEqualTo("app-123");
        assertThat(root.path("payload").path("cloudProfile").asText()).isEqualTo("assistant_square");
    }

    @Test
    @DisplayName("buildInvoke(chat) extracts sendUserAccount from command.payload to CloudRequestContext")
    void buildInvoke_chat_extractsSendUserAccount() throws Exception {
        String payload = "{\"content\":\"hello\",\"sendUserAccount\":\"user-001\","
                + "\"assistantAccount\":\"asst-1\",\"toolSessionId\":\"tool-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "owner-1", "session-1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-123");

        String result = strategy.buildInvoke(command, info);

        CloudRequestContext ctx = capturedContext();
        assertEquals("user-001", ctx.getSendUserAccount());
        assertEquals("asst-1", ctx.getAssistantAccount());
        assertEquals("asst-1", objectMapper.readTree(result).path("assistantAccount").asText());
        assertEquals("app-123", objectMapper.readTree(result).path("businessTag").asText());
    }

    @Test
    @DisplayName("buildInvoke(chat) falls back to command assistantAccount when payload omits it")
    void buildInvoke_chat_fallsBackToCommandAssistantAccount() {
        String payload = "{\"content\":\"hello\",\"sendUserAccount\":\"user-001\",\"toolSessionId\":\"tool-1\"}";
        InvokeCommand command = new InvokeCommand(null, "owner-1", "session-1", "chat", payload,
                null, "im", "dm", "dm-001", null, "asst-from-command", "asst-from-command");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-123");

        strategy.buildInvoke(command, info);

        assertEquals("asst-from-command", capturedContext().getAssistantAccount());
    }

    // ========== businessExtParam passthrough（6 cases） ==========

    @Test
    @DisplayName("buildInvoke(chat) with businessExtParam → passed through to extParameters.businessExtParam")
    void buildInvoke_chat_passesBusinessExtParam() {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":{\"a\":1,\"k\":[1,2]}," +
                "\"toolSessionId\":\"cloud-001\",\"assistantAccount\":\"asst-1\"," +
                "\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        Map<String, Object> ext = capturedContext().getExtParameters();
        assertNotNull(ext);
        JsonNode bep = (JsonNode) ext.get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(1, bep.get("a").asInt());
        assertTrue(bep.get("k").isArray());
        JsonNode pep = (JsonNode) ext.get("platformExtParam");
        assertTrue(pep.isObject());
        // PR1: platformExtParam 含三字段 key（domain/domainType/businessSessionId 均未传 → JSON null）
        assertEquals(3, pep.size());
        assertTrue(pep.get("businessSessionDomain").isNull());
        assertTrue(pep.get("businessSessionType").isNull());
        assertTrue(pep.get("businessSessionId").isNull());
    }

    @Test
    @DisplayName("buildInvoke(chat) without businessExtParam → fallback {}")
    void buildInvoke_chat_missingBusinessExtParam() {
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-001\"," +
                "\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        Map<String, Object> ext = capturedContext().getExtParameters();
        assertNotNull(ext);
        JsonNode bep = (JsonNode) ext.get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke(question_reply) with businessExtParam → passed through")
    void buildInvoke_questionReply_passesBusinessExtParam() {
        String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\"," +
                "\"businessExtParam\":{\"q\":\"x\"},\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "question_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertEquals("x", bep.get("q").asText());
    }

    @Test
    @DisplayName("buildInvoke(question_reply) missing businessExtParam → fallback {}")
    void buildInvoke_questionReply_missingBusinessExtParam() {
        String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "question_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke(permission_reply) with businessExtParam → passed through")
    void buildInvoke_permissionReply_passesBusinessExtParam() {
        String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\"," +
                "\"businessExtParam\":{\"p\":true},\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "permission_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertTrue(bep.get("p").asBoolean());
    }

    @Test
    @DisplayName("buildInvoke(permission_reply) missing businessExtParam → fallback {}")
    void buildInvoke_permissionReply_missingBusinessExtParam() {
        String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "permission_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke businessExtParam as string → fallback {}")
    void buildInvoke_businessExtParam_asString_fallback() {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":\"abc\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke businessExtParam as array → fallback {}")
    void buildInvoke_businessExtParam_asArray_fallback() {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":[1,2,3],\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }

    // ========== PR1: platformExtParam 三字段填充（2 cases） ==========

    @Test
    @DisplayName("buildInvoke with InvokeCommand domain/domainType/businessSessionId → platformExtParam 三字段填充")
    void buildInvoke_platformExtParam_allThreeFieldsPopulated() {
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-001\","
                + "\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\"}";
        // 9 参构造器：domain="im", domainType="group", businessSessionId="wx-group-abc"
        InvokeCommand command = new InvokeCommand(
                "ak-1", "u-1", "1", "chat", payload,
                null, "im", "group", "wx-group-abc");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode pep = (JsonNode) capturedContext().getExtParameters().get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(3, pep.size());
        assertEquals("im", pep.get("businessSessionDomain").asText());
        assertEquals("group", pep.get("businessSessionType").asText());
        assertEquals("wx-group-abc", pep.get("businessSessionId").asText());
    }

    @Test
    @DisplayName("buildInvoke with InvokeCommand 5-arg (no domain fields) → platformExtParam 三字段全 JSON null（key 保留）")
    void buildInvoke_platformExtParam_missingFieldsSerializedAsNull() {
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-001\"}";
        // 5 参构造器：domain/domainType/businessSessionId 均 null
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode pep = (JsonNode) capturedContext().getExtParameters().get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(3, pep.size());
        assertTrue(pep.get("businessSessionDomain").isNull());
        assertTrue(pep.get("businessSessionType").isNull());
        assertTrue(pep.get("businessSessionId").isNull());
    }

    // ========== parseAnswers helper（4 cases） ==========

    @Test
    @DisplayName("parseAnswers stringified nested array → preserved as-is")
    void parseAnswers_stringifiedNestedArray_preservedAsIs() {
        List<List<String>> result = strategy.parseAnswers("[[\"A\"],[\"B\",\"C\"]]");
        assertThat(result).containsExactly(List.of("A"), List.of("B", "C"));
    }

    @Test
    @DisplayName("parseAnswers stringified 1D array → wrapped as outer")
    void parseAnswers_stringified1DArray_wrappedToOuter() {
        List<List<String>> result = strategy.parseAnswers("[\"A\",\"B\"]");
        assertThat(result).containsExactly(List.of("A", "B"));
    }

    @Test
    @DisplayName("parseAnswers plain text → wrapped to 2D")
    void parseAnswers_plainText_wrappedToDoubleArray() {
        List<List<String>> result = strategy.parseAnswers("plain text");
        assertThat(result).containsExactly(List.of("plain text"));
    }

    @Test
    @DisplayName("parseAnswers blank/null → returns single empty")
    void parseAnswers_blankOrNull_returnsSingleEmpty() {
        assertThat(strategy.parseAnswers(null)).containsExactly(List.of(""));
        assertThat(strategy.parseAnswers("")).containsExactly(List.of(""));
        assertThat(strategy.parseAnswers("   ")).containsExactly(List.of(""));
    }

    // ========== action routes reply fields（3 cases） ==========

    @Test
    @DisplayName("buildInvoke(question_reply) writes action=question_reply")
    void buildInvoke_questionReply_writesAction() throws Exception {
        String payload = "{\"toolSessionId\":\"ts-1\",\"toolCallId\":\"call-q\",\"answer\":\"[[\\\"A\\\"]]\"}";
        InvokeCommand command = new InvokeCommand("ak1", "u1", "s1",
                GatewayActions.QUESTION_REPLY, payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        String result = strategy.buildInvoke(command, info);

        assertNotNull(result);
        JsonNode root = objectMapper.readTree(result);
        assertThat(root.path("action").asText()).isEqualTo("question_reply");
    }

    @Test
    @DisplayName("buildInvoke(permission_reply) writes action=permission_reply")
    void buildInvoke_permissionReply_writesAction() throws Exception {
        String payload = "{\"toolSessionId\":\"ts-1\",\"permissionId\":\"perm-1\",\"response\":\"once\"}";
        InvokeCommand command = new InvokeCommand("ak1", "u1", "s1",
                GatewayActions.PERMISSION_REPLY, payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        String result = strategy.buildInvoke(command, info);

        assertNotNull(result);
        JsonNode root = objectMapper.readTree(result);
        assertThat(root.path("action").asText()).isEqualTo("permission_reply");
    }

    @Test
    @DisplayName("buildInvoke(chat) does not write reply fields; cloudRequest has no replyContext")
    void buildInvoke_chat_doesNotWriteReplyFields() throws Exception {
        String payload = "{\"toolSessionId\":\"ts-1\",\"text\":\"hello\"}";
        InvokeCommand command = new InvokeCommand("ak1", "u1", "s1",
                GatewayActions.CHAT, payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        ObjectNode emptyCr = objectMapper.createObjectNode();
        when(defaultStrategy.build(any(CloudRequestContext.class))).thenReturn(emptyCr);

        String result = strategy.buildInvoke(command, info);

        assertNotNull(result);
        JsonNode root = objectMapper.readTree(result);
        JsonNode cr = root.path("payload").path("cloudRequest");
        assertThat(cr.has("replyContext")).isFalse();
    }

    @Test
    @DisplayName("buildInvoke payload invalid JSON → no throw, fallback {}")
    void buildInvoke_payloadInvalidJson_fallback() {
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", "not-a-json");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        strategy.buildInvoke(command, info);

        JsonNode bep = (JsonNode) capturedContext().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }
}
