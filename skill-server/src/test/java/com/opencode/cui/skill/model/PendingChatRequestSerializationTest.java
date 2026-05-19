package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3 allowed-slash-commands 任务的 {@link PendingChatRequest} 序列化测试。
 *
 * <p>覆盖 PRD AC：
 * <ul>
 *   <li>AC8 老格式 entry（无 allowedSlashCommands 字段）反序列化 → 字段 = null（不下发该 platformExtParam key）</li>
 *   <li>新 entry 序列化 / 反序列化往返一致（含 allowedSlashCommands）</li>
 *   <li>显式 JSON null / 空数组 / 非空数组 三态</li>
 * </ul>
 */
@DisplayName("PendingChatRequest serialization (v3 allowed-slash-commands)")
class PendingChatRequestSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== AC8: 老 entry 反序列化 ====================

    @Test
    @DisplayName("AC8 老格式 entry（缺 allowedSlashCommands 字段）→ allowedSlashCommands = null")
    void deserialize_legacyEntryMissingAllowedSlashKey_fieldIsNull() throws Exception {
        // 这是 v3 之前生产环境写入 Redis pending list 的真实形状
        String legacyJson = "{"
                + "\"text\":\"hello\","
                + "\"assistantAccount\":\"a-01\","
                + "\"sendUserAccount\":\"u-01\","
                + "\"imGroupId\":\"g-1\","
                + "\"messageId\":\"1717939200000\","
                + "\"businessExtParam\":{\"foo\":\"bar\"},"
                + "\"businessSessionDomain\":\"im\","
                + "\"businessSessionType\":\"group\""
                + "}";

        PendingChatRequest req = objectMapper.readValue(legacyJson, PendingChatRequest.class);

        // 6 个原有字段都还原
        assertNotNull(req);
        assertEquals("hello", req.text());
        assertEquals("a-01", req.assistantAccount());
        assertEquals("u-01", req.sendUserAccount());
        assertEquals("g-1", req.imGroupId());
        assertEquals("1717939200000", req.messageId());
        assertEquals("im", req.businessSessionDomain());
        assertEquals("group", req.businessSessionType());
        // AC8: 新字段缺失自动 → null（不下发 platformExtParam.allowedSlashCommands）
        assertNull(req.allowedSlashCommands());
    }

    @Test
    @DisplayName("AC8 老格式 entry / 显式 allowedSlashCommands=null → 字段保持 null")
    void deserialize_explicitNullField_isStillNull() throws Exception {
        String json = "{"
                + "\"text\":\"hello\","
                + "\"assistantAccount\":\"a-01\","
                + "\"sendUserAccount\":\"u-01\","
                + "\"imGroupId\":\"g-1\","
                + "\"messageId\":\"1717939200000\","
                + "\"businessExtParam\":null,"
                + "\"businessSessionDomain\":\"im\","
                + "\"businessSessionType\":\"group\","
                + "\"allowedSlashCommands\":null"
                + "}";

        PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

        assertNotNull(req);
        assertNull(req.allowedSlashCommands());
    }

    // ==================== 新 entry 反序列化 ====================

    @Test
    @DisplayName("新 entry：含 allowedSlashCommands JSON 数组 → 反序列化为 list")
    void deserialize_v3EntryWithList_returnsList() throws Exception {
        String json = "{"
                + "\"text\":\"hello\","
                + "\"assistantAccount\":\"a-01\","
                + "\"sendUserAccount\":\"u-01\","
                + "\"imGroupId\":\"g-1\","
                + "\"messageId\":\"1717939200000\","
                + "\"businessExtParam\":null,"
                + "\"businessSessionDomain\":\"im\","
                + "\"businessSessionType\":\"group\","
                + "\"allowedSlashCommands\":[\"plan\",\"ask\",\"run\"]"
                + "}";

        PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

        assertNotNull(req);
        assertNotNull(req.allowedSlashCommands());
        assertEquals(3, req.allowedSlashCommands().size());
        assertEquals("plan", req.allowedSlashCommands().get(0));
        assertEquals("ask", req.allowedSlashCommands().get(1));
        assertEquals("run", req.allowedSlashCommands().get(2));
    }

    @Test
    @DisplayName("新 entry：allowedSlashCommands 是空数组 → 反序列化为空 List（不是 null）")
    void deserialize_v3EntryWithEmptyArray_returnsEmptyList() throws Exception {
        String json = "{"
                + "\"text\":\"hello\","
                + "\"assistantAccount\":\"a-01\","
                + "\"sendUserAccount\":\"u-01\","
                + "\"imGroupId\":\"g-1\","
                + "\"messageId\":\"1717939200000\","
                + "\"businessExtParam\":null,"
                + "\"businessSessionDomain\":\"im\","
                + "\"businessSessionType\":\"group\","
                + "\"allowedSlashCommands\":[]"
                + "}";

        PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);

        assertNotNull(req.allowedSlashCommands());
        assertTrue(req.allowedSlashCommands().isEmpty());
    }

    // ==================== 序列化 / 往返 ====================

    @Test
    @DisplayName("新 entry / list 非空 → 序列化含 allowedSlashCommands JSON 数组")
    void serialize_v3EntryWithList_jsonContainsArray() throws Exception {
        PendingChatRequest req = new PendingChatRequest(
                "hello", "a-01", "u-01", "g-1", "1717939200000",
                null, "im", "group",
                List.of("plan", "ask"));

        String json = objectMapper.writeValueAsString(req);

        assertTrue(json.contains("\"allowedSlashCommands\":[\"plan\",\"ask\"]"));
    }

    @Test
    @DisplayName("新 entry / list = null → 序列化 allowedSlashCommands 为 JSON null")
    void serialize_v3EntryWithNullList_jsonContainsNull() throws Exception {
        PendingChatRequest req = new PendingChatRequest(
                "hello", "a-01", "u-01", "g-1", "1717939200000",
                null, "im", "group", null);

        String json = objectMapper.writeValueAsString(req);

        // Jackson 默认输出 "allowedSlashCommands":null（key 仍存在）
        assertTrue(json.contains("\"allowedSlashCommands\":null"));
    }

    @Test
    @DisplayName("往返：序列化 + 反序列化保持 list 内容一致")
    void roundTrip_listPreserved() throws Exception {
        PendingChatRequest original = new PendingChatRequest(
                "hello", "a-01", "u-01", "g-1", "1717939200000",
                null, "im", "group",
                List.of("plan", "ask", "run"));

        String json = objectMapper.writeValueAsString(original);
        PendingChatRequest deserialized = objectMapper.readValue(json, PendingChatRequest.class);

        assertEquals(original.allowedSlashCommands(), deserialized.allowedSlashCommands());
        assertEquals(original.text(), deserialized.text());
        assertEquals(original.businessSessionDomain(), deserialized.businessSessionDomain());
    }

    // ==================== 8 参 secondary 构造器 + 默认 null ====================

    @Test
    @DisplayName("8 参 secondary constructor → allowedSlashCommands 默认 null")
    void eightArgSecondary_allowedSlashDefaultsNull() {
        PendingChatRequest req = new PendingChatRequest(
                "hello", "a-01", "u-01", "g-1", "1717939200000",
                null, "im", "group");

        assertNull(req.allowedSlashCommands());
    }
}
