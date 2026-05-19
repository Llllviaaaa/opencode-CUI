package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingChatRequest} 单元测试（PR1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>record canonical 构造 + 字段 accessor</li>
 *   <li>Jackson JSON 序列化 / 反序列化（含字段顺序、businessExtParam JsonNode、null 字段）</li>
 *   <li>{@code fromSessionFallback}：群聊 / 单聊 / 空白字段校验失败</li>
 *   <li>回归断言：{@code fromSessionFallback} 取的是 {@code businessSessionId}，不是 {@code session.getId()}</li>
 * </ul>
 */
@DisplayName("PendingChatRequest")
class PendingChatRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("canonical record")
    class CanonicalRecord {

        @Test
        @DisplayName("8 参数构造器：所有字段按入参赋值，accessor 返回原值")
        void constructor_assignsAllFields() throws Exception {
            JsonNode ext = objectMapper.readTree("{\"foo\":\"bar\"}");
            PendingChatRequest req = new PendingChatRequest(
                    "hello",
                    "assist-01",
                    "user-real",
                    "group-001",
                    "1717939200000",
                    ext,
                    "im",
                    "group");

            assertEquals("hello", req.text());
            assertEquals("assist-01", req.assistantAccount());
            assertEquals("user-real", req.sendUserAccount());
            assertEquals("group-001", req.imGroupId());
            assertEquals("1717939200000", req.messageId());
            assertEquals(ext, req.businessExtParam());
            assertEquals("im", req.businessSessionDomain());
            assertEquals("group", req.businessSessionType());
        }

        @Test
        @DisplayName("imGroupId / businessExtParam / businessSessionDomain / businessSessionType 允许 null")
        void constructor_allowsNullableFields() {
            PendingChatRequest req = new PendingChatRequest(
                    "hi", "assist-01", "user-owner", null, "msg-1", null, null, null);

            assertNull(req.imGroupId());
            assertNull(req.businessExtParam());
            assertNull(req.businessSessionDomain());
            assertNull(req.businessSessionType());
        }

        @Test
        @DisplayName("equals / hashCode：8 字段全部参与比较")
        void equalsHashCode_considersAllFields() {
            PendingChatRequest a = new PendingChatRequest(
                    "hi", "assist-01", "user", "g-1", "m-1", null, "im", "group");
            PendingChatRequest b = new PendingChatRequest(
                    "hi", "assist-01", "user", "g-1", "m-1", null, "im", "group");
            PendingChatRequest different = new PendingChatRequest(
                    "hi", "assist-02", "user", "g-1", "m-1", null, "im", "group");
            PendingChatRequest differentDomain = new PendingChatRequest(
                    "hi", "assist-01", "user", "g-1", "m-1", null, "miniapp", "group");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, different);
            assertNotEquals(a, differentDomain);
        }
    }

    @Nested
    @DisplayName("Jackson roundtrip")
    class JacksonRoundtrip {

        @Test
        @DisplayName("序列化 → JSON 字段按 record 声明顺序：text → assistantAccount → ... → businessSessionType")
        void serialize_preservesDeclarationOrder() throws Exception {
            JsonNode ext = objectMapper.readTree("{\"foo\":\"bar\"}");
            PendingChatRequest req = new PendingChatRequest(
                    "hello",
                    "assist-01",
                    "user-real",
                    "group-001",
                    "1717939200000",
                    ext,
                    "im",
                    "group");

            String json = objectMapper.writeValueAsString(req);

            int idxText = json.indexOf("\"text\"");
            int idxAssistant = json.indexOf("\"assistantAccount\"");
            int idxSender = json.indexOf("\"sendUserAccount\"");
            int idxGroup = json.indexOf("\"imGroupId\"");
            int idxMsgId = json.indexOf("\"messageId\"");
            int idxExt = json.indexOf("\"businessExtParam\"");
            int idxDomain = json.indexOf("\"businessSessionDomain\"");
            int idxType = json.indexOf("\"businessSessionType\"");

            assertTrue(idxText >= 0, "text key missing: " + json);
            assertTrue(idxText < idxAssistant, "text before assistantAccount: " + json);
            assertTrue(idxAssistant < idxSender, "assistantAccount before sendUserAccount: " + json);
            assertTrue(idxSender < idxGroup, "sendUserAccount before imGroupId: " + json);
            assertTrue(idxGroup < idxMsgId, "imGroupId before messageId: " + json);
            assertTrue(idxMsgId < idxExt, "messageId before businessExtParam: " + json);
            assertTrue(idxExt < idxDomain, "businessExtParam before businessSessionDomain: " + json);
            assertTrue(idxDomain < idxType, "businessSessionDomain before businessSessionType: " + json);
        }

        @Test
        @DisplayName("反序列化 → 6 字段全部还原（businessExtParam 是 ObjectNode）")
        void deserialize_restoresAllFields() throws Exception {
            String json = "{"
                    + "\"text\":\"hello\","
                    + "\"assistantAccount\":\"assist-01\","
                    + "\"sendUserAccount\":\"user-real\","
                    + "\"imGroupId\":\"group-001\","
                    + "\"messageId\":\"1717939200000\","
                    + "\"businessExtParam\":{\"foo\":\"bar\",\"n\":1}"
                    + "}";

            PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

            assertEquals("hello", req.text());
            assertEquals("assist-01", req.assistantAccount());
            assertEquals("user-real", req.sendUserAccount());
            assertEquals("group-001", req.imGroupId());
            assertEquals("1717939200000", req.messageId());

            assertNotNull(req.businessExtParam());
            assertInstanceOf(ObjectNode.class, req.businessExtParam());
            assertEquals("bar", req.businessExtParam().get("foo").asText());
            assertEquals(1, req.businessExtParam().get("n").asInt());
        }

        @Test
        @DisplayName("反序列化：imGroupId = JSON null → Java null；businessExtParam = JSON null → NullNode（Jackson 默认行为）")
        void deserialize_nullFields_stringIsNullNodeIsNullNode() throws Exception {
            // 关键约定：Jackson 把 JSON `null` 映射到 JsonNode 字段时返回 NullNode（!= Java null）。
            // 上游消费方（PR2 retryPendingMessages / dispatchChatToGateway）需要把 businessExtParam
            // 视作 "JsonNode != null && !isNull()" 才算"有 ext"；否则按 null 处理。
            // 本测试把这个行为锁死，避免上游误以为反序列化会自动归一到 Java null。
            String json = "{"
                    + "\"text\":\"hello\","
                    + "\"assistantAccount\":\"assist-01\","
                    + "\"sendUserAccount\":\"user-real\","
                    + "\"imGroupId\":null,"
                    + "\"messageId\":\"m-1\","
                    + "\"businessExtParam\":null"
                    + "}";

            PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

            assertNull(req.imGroupId(),
                    "String field with JSON null deserializes to Java null");
            assertNotNull(req.businessExtParam(),
                    "JsonNode field with JSON null deserializes to NullNode (Jackson default), not Java null");
            assertTrue(req.businessExtParam().isNull(),
                    "businessExtParam should be NullNode whose isNull() == true");
        }

        @Test
        @DisplayName("反序列化：businessExtParam 字段缺失 → record 字段为 Java null")
        void deserialize_missingBusinessExtParam_isJavaNull() throws Exception {
            String json = "{"
                    + "\"text\":\"hello\","
                    + "\"assistantAccount\":\"assist-01\","
                    + "\"sendUserAccount\":\"user-real\","
                    + "\"imGroupId\":\"g\","
                    + "\"messageId\":\"m-1\""
                    + "}";

            PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

            assertNull(req.businessExtParam(),
                    "missing JSON field deserializes to Java null (no NullNode)");
        }

        @Test
        @DisplayName("write → read 完整 roundtrip：所有字段保持一致")
        void writeThenRead_isLossless() throws Exception {
            ObjectNode ext = objectMapper.createObjectNode();
            ext.put("topicId", 42);
            ext.put("source", "im");

            PendingChatRequest original = new PendingChatRequest(
                    "round-trip text",
                    "assist-02",
                    "sender-real",
                    "group-x",
                    "msg-roundtrip",
                    ext,
                    "im",
                    "group");

            String json = objectMapper.writeValueAsString(original);
            PendingChatRequest restored = objectMapper.readValue(json, PendingChatRequest.class);

            assertEquals(original.text(), restored.text());
            assertEquals(original.assistantAccount(), restored.assistantAccount());
            assertEquals(original.sendUserAccount(), restored.sendUserAccount());
            assertEquals(original.imGroupId(), restored.imGroupId());
            assertEquals(original.messageId(), restored.messageId());
            assertEquals(original.businessExtParam(), restored.businessExtParam());
            assertEquals(original.businessSessionDomain(), restored.businessSessionDomain());
            assertEquals(original.businessSessionType(), restored.businessSessionType());
            assertEquals(original, restored);
        }

        @Test
        @DisplayName("PR2 兼容性：老格式 JSON entry（缺 businessSessionDomain / businessSessionType）反序列化 → 新字段为 Java null")
        void deserialize_legacyEntryWithoutNewFields_isJavaNull() throws Exception {
            // 老格式 entry: 仅含原 6 字段, 缺 businessSessionDomain / businessSessionType
            String legacyJson = "{"
                    + "\"text\":\"legacy text\","
                    + "\"assistantAccount\":\"assist-old\","
                    + "\"sendUserAccount\":\"user-old\","
                    + "\"imGroupId\":\"g-old\","
                    + "\"messageId\":\"m-old\","
                    + "\"businessExtParam\":null"
                    + "}";

            PendingChatRequest req = objectMapper.readValue(legacyJson, PendingChatRequest.class);

            assertEquals("legacy text", req.text());
            assertEquals("assist-old", req.assistantAccount());
            assertNull(req.businessSessionDomain(),
                    "missing businessSessionDomain must deserialize to Java null");
            assertNull(req.businessSessionType(),
                    "missing businessSessionType must deserialize to Java null");
        }

        @Test
        @DisplayName("PR2 新格式：含 businessSessionDomain / businessSessionType 的 JSON entry 反序列化正确")
        void deserialize_newEntryWithSessionFields_restoresAll() throws Exception {
            String newJson = "{"
                    + "\"text\":\"hello\","
                    + "\"assistantAccount\":\"assist-01\","
                    + "\"sendUserAccount\":\"user-real\","
                    + "\"imGroupId\":\"g-1\","
                    + "\"messageId\":\"m-1\","
                    + "\"businessExtParam\":null,"
                    + "\"businessSessionDomain\":\"im\","
                    + "\"businessSessionType\":\"group\""
                    + "}";

            PendingChatRequest req = objectMapper.readValue(newJson, PendingChatRequest.class);

            assertEquals("im", req.businessSessionDomain());
            assertEquals("group", req.businessSessionType());
        }
    }

    @Nested
    @DisplayName("fromSessionFallback")
    class FromSessionFallback {

        @Test
        @DisplayName("群聊 session：imGroupId == businessSessionId，sender == owner userId")
        void groupSession_imGroupIdFromBusinessSessionId() {
            SkillSession session = buildSession(
                    /* id */ 9999L,
                    /* userId (owner) */ "owner-welink-001",
                    /* assistantAccount */ "assist-01",
                    /* domain */ SkillSession.DOMAIN_IM,
                    /* type */ SkillSession.SESSION_TYPE_GROUP,
                    /* businessSessionId */ "group-real-id-777");

            PendingChatRequest req = PendingChatRequest.fromSessionFallback(session, "fallback text");

            assertEquals("fallback text", req.text());
            assertEquals("assist-01", req.assistantAccount());
            assertEquals("owner-welink-001", req.sendUserAccount());
            assertEquals("group-real-id-777", req.imGroupId());
            assertEquals(SkillSession.DOMAIN_IM, req.businessSessionDomain(),
                    "PR2: businessSessionDomain must come from session.getBusinessSessionDomain()");
            assertEquals(SkillSession.SESSION_TYPE_GROUP, req.businessSessionType(),
                    "PR2: businessSessionType must come from session.getBusinessSessionType()");
            assertNotNull(req.messageId());
            assertFalse(req.messageId().isBlank());
            assertNull(req.businessExtParam());
        }

        @Test
        @DisplayName("单聊 session：imGroupId == null")
        void directSession_imGroupIdIsNull() {
            SkillSession session = buildSession(
                    /* id */ 100L,
                    /* userId (owner) */ "owner-direct-002",
                    /* assistantAccount */ "assist-direct",
                    /* domain */ SkillSession.DOMAIN_IM,
                    /* type */ SkillSession.SESSION_TYPE_DIRECT,
                    /* businessSessionId */ "direct-business-id");

            PendingChatRequest req = PendingChatRequest.fromSessionFallback(session, "hi");

            assertEquals("hi", req.text());
            assertEquals("assist-direct", req.assistantAccount());
            assertEquals("owner-direct-002", req.sendUserAccount());
            assertNull(req.imGroupId(),
                    "direct session must have null imGroupId");
            assertNull(req.businessExtParam());
        }

        @Test
        @DisplayName("回归 M3：imGroupId 取的是 businessSessionId，不是 session.getId()")
        void groupSession_imGroupIdMustNotBeSkillPrimaryKey() {
            // 关键：故意让 id (skill 主键) 与 businessSessionId 不同，确认 fallback 取后者。
            Long skillPrimaryKey = 123456789L;
            String businessGroupId = "im-group-9999";

            SkillSession session = buildSession(
                    skillPrimaryKey,
                    "owner-x",
                    "assist-x",
                    SkillSession.DOMAIN_IM,
                    SkillSession.SESSION_TYPE_GROUP,
                    businessGroupId);

            PendingChatRequest req = PendingChatRequest.fromSessionFallback(session, "t");

            assertEquals(businessGroupId, req.imGroupId(),
                    "imGroupId must be businessSessionId, not skill primary key");
            assertNotEquals(String.valueOf(skillPrimaryKey), req.imGroupId(),
                    "regression: imGroupId must not equal SkillSession.getId()");
        }

        @Test
        @DisplayName("assistantAccount 空白 → 抛 IllegalArgumentException")
        void blankAssistantAccount_throwsIAE() {
            SkillSession session = buildSession(
                    1L, "owner", "  ", SkillSession.DOMAIN_IM,
                    SkillSession.SESSION_TYPE_DIRECT, "biz-1");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> PendingChatRequest.fromSessionFallback(session, "t"));
            assertTrue(ex.getMessage().contains("non-blank"),
                    "IAE message should mention non-blank validation, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("assistantAccount null → 抛 IllegalArgumentException")
        void nullAssistantAccount_throwsIAE() {
            SkillSession session = buildSession(
                    1L, "owner", null, SkillSession.DOMAIN_IM,
                    SkillSession.SESSION_TYPE_DIRECT, "biz-1");

            assertThrows(IllegalArgumentException.class,
                    () -> PendingChatRequest.fromSessionFallback(session, "t"));
        }

        @Test
        @DisplayName("userId 空白 → 抛 IllegalArgumentException")
        void blankUserId_throwsIAE() {
            SkillSession session = buildSession(
                    1L, "", "assist", SkillSession.DOMAIN_IM,
                    SkillSession.SESSION_TYPE_DIRECT, "biz-1");

            assertThrows(IllegalArgumentException.class,
                    () -> PendingChatRequest.fromSessionFallback(session, "t"));
        }

        @Test
        @DisplayName("userId null → 抛 IllegalArgumentException")
        void nullUserId_throwsIAE() {
            SkillSession session = buildSession(
                    1L, null, "assist", SkillSession.DOMAIN_IM,
                    SkillSession.SESSION_TYPE_DIRECT, "biz-1");

            assertThrows(IllegalArgumentException.class,
                    () -> PendingChatRequest.fromSessionFallback(session, "t"));
        }

        @Test
        @DisplayName("session 为 null → 抛 IllegalArgumentException")
        void nullSession_throwsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> PendingChatRequest.fromSessionFallback(null, "t"));
        }

        @Test
        @DisplayName("非 IM 域 group session 视为非群聊：imGroupId == null")
        void nonImGroupSession_imGroupIdIsNull() {
            // miniapp 域 + group 类型不会出现在生产，仅作为边界用例验证 isImGroupSession() 语义。
            SkillSession session = buildSession(
                    1L, "owner", "assist", SkillSession.DOMAIN_MINIAPP,
                    SkillSession.SESSION_TYPE_GROUP, "biz-x");

            PendingChatRequest req = PendingChatRequest.fromSessionFallback(session, "t");

            assertNull(req.imGroupId(),
                    "non-IM domain must not produce imGroupId even if type is group");
        }

        private SkillSession buildSession(Long id, String userId, String assistantAccount,
                String domain, String sessionType, String businessSessionId) {
            SkillSession s = new SkillSession();
            s.setId(id);
            s.setUserId(userId);
            s.setAssistantAccount(assistantAccount);
            s.setBusinessSessionDomain(domain);
            s.setBusinessSessionType(sessionType);
            s.setBusinessSessionId(businessSessionId);
            return s;
        }
    }
}
