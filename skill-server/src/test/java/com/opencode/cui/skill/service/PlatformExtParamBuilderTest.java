package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR1: 验证 {@link PlatformExtParamBuilder} 三字段全填 / 部分缺失 / 全缺失 → JSON 形态。
 *
 * <p>不变量：
 * <ul>
 *   <li>三字段 key 始终出现（哪怕 null）</li>
 *   <li>null 值序列化为 JSON {@code null}（{@code NullNode}），不省略 key</li>
 *   <li>非 null 值序列化为 TextNode</li>
 * </ul>
 */
@DisplayName("PlatformExtParamBuilder")
class PlatformExtParamBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("三字段全填 → 三个 TextNode + bizRobotTag null")
    void build_allThreeFields_returnsTextNodes() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-group-abc");

        assertEquals(4, node.size());
        assertEquals("im", node.get("businessSessionDomain").asText());
        assertEquals("group", node.get("businessSessionType").asText());
        assertEquals("wx-group-abc", node.get("businessSessionId").asText());
        assertTrue(node.get("bizRobotTag").isNull());
        // 验证均非 null node
        assertFalse(node.get("businessSessionDomain").isNull());
        assertFalse(node.get("businessSessionType").isNull());
        assertFalse(node.get("businessSessionId").isNull());
    }

    @Test
    @DisplayName("三字段全 null → 三个 JSON null（NullNode），key 仍保留")
    void build_allNullFields_keysRetainedAsJsonNull() {
        ObjectNode node = PlatformExtParamBuilder.build(objectMapper, null, null, null);

        assertEquals(4, node.size());
        assertTrue(node.has("businessSessionDomain"));
        assertTrue(node.has("businessSessionType"));
        assertTrue(node.has("businessSessionId"));
        assertTrue(node.has("bizRobotTag"));
        assertTrue(node.get("businessSessionDomain").isNull());
        assertTrue(node.get("businessSessionType").isNull());
        assertTrue(node.get("businessSessionId").isNull());
        assertTrue(node.get("bizRobotTag").isNull());
    }

    @Test
    @DisplayName("部分缺失（仅 domain 有值）→ 其余字段 JSON null")
    void build_onlyDomainPresent_othersAreNull() {
        ObjectNode node = PlatformExtParamBuilder.build(objectMapper, "external", null, null);

        assertEquals(4, node.size());
        assertEquals("external", node.get("businessSessionDomain").asText());
        assertTrue(node.get("businessSessionType").isNull());
        assertTrue(node.get("businessSessionId").isNull());
        assertTrue(node.get("bizRobotTag").isNull());
    }

    @Test
    @DisplayName("部分缺失（domain + type 有值）→ businessSessionId JSON null")
    void build_domainAndTypePresent_idNull() {
        ObjectNode node = PlatformExtParamBuilder.build(objectMapper, "im", "direct", null);

        assertEquals(4, node.size());
        assertEquals("im", node.get("businessSessionDomain").asText());
        assertEquals("direct", node.get("businessSessionType").asText());
        assertTrue(node.get("businessSessionId").isNull());
        assertTrue(node.get("bizRobotTag").isNull());
    }

    @Test
    @DisplayName("空字符串 != null：空字符串保留为 TextNode")
    void build_emptyStringIsNotNull_preservedAsTextNode() {
        ObjectNode node = PlatformExtParamBuilder.build(objectMapper, "", "", "");

        assertEquals(4, node.size());
        // 空字符串保留为 TextNode（非 NullNode），与协议契约对齐
        assertFalse(node.get("businessSessionDomain").isNull());
        assertEquals("", node.get("businessSessionDomain").asText());
        assertFalse(node.get("businessSessionType").isNull());
        assertEquals("", node.get("businessSessionType").asText());
        assertFalse(node.get("businessSessionId").isNull());
        assertEquals("", node.get("businessSessionId").asText());
        assertTrue(node.get("bizRobotTag").isNull());
    }

    @Test
    @DisplayName("序列化为 JSON 字符串：null 字段输出为 JSON null literal")
    void build_serializesNullAsJsonNullLiteral() throws Exception {
        ObjectNode node = PlatformExtParamBuilder.build(objectMapper, "im", null, "session-1");
        String json = objectMapper.writeValueAsString(node);

        // 三字段全保留，null 序列化为 JSON null
        assertTrue(json.contains("\"businessSessionDomain\":\"im\""));
        assertTrue(json.contains("\"businessSessionType\":null"));
        assertTrue(json.contains("\"businessSessionId\":\"session-1\""));
    }

    // ==================== v3 allowed-slash-commands: 5 参重载 ====================

    @Test
    @DisplayName("5 参 / list=null → 不出现 allowedSlashCommands key（与决策 6 一致）")
    void build5_listNull_keyAbsent() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1", null);

        assertEquals(4, node.size());
        assertTrue(node.get("bizRobotTag").isNull());
        assertFalse(node.has("allowedSlashCommands"));
    }

    @Test
    @DisplayName("5 参 / list 非空 → allowedSlashCommands JSON 数组，顺序一致")
    void build5_listNonEmpty_jsonArrayInOrder() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1",
                Arrays.asList("plan", "ask", "run"));

        assertEquals(5, node.size());
        assertTrue(node.get("bizRobotTag").isNull());
        assertTrue(node.get("allowedSlashCommands").isArray());
        assertEquals(3, node.get("allowedSlashCommands").size());
        assertEquals("plan", node.get("allowedSlashCommands").get(0).asText());
        assertEquals("ask", node.get("allowedSlashCommands").get(1).asText());
        assertEquals("run", node.get("allowedSlashCommands").get(2).asText());
    }

    @Test
    @DisplayName("5 参 / list 为空 → 不出现 key（与 null 同语义）")
    void build5_listEmpty_keyAbsent() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1", Collections.emptyList());

        assertEquals(4, node.size());
        assertTrue(node.get("bizRobotTag").isNull());
        assertFalse(node.has("allowedSlashCommands"));
    }

    @Test
    @DisplayName("5 参 / 三字段语义与 4 参重载完全一致")
    void build5_threeFieldsBehaviorMatchesBuild4() {
        ObjectNode node5 = PlatformExtParamBuilder.build(
                objectMapper, "im", null, "wx-g-1", null);

        // 三字段 + bizRobotTag 保留 key + null 序列化为 JSON null
        assertEquals(4, node5.size());
        assertEquals("im", node5.get("businessSessionDomain").asText());
        assertTrue(node5.get("businessSessionType").isNull());
        assertEquals("wx-g-1", node5.get("businessSessionId").asText());
        assertTrue(node5.get("bizRobotTag").isNull());
    }

    @Test
    @DisplayName("5 参 / list 非空 + 序列化为 JSON 字符串")
    void build5_serializesArrayLiteral() throws Exception {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1",
                List.of("plan", "ask"));
        String json = objectMapper.writeValueAsString(node);

        assertTrue(json.contains("\"allowedSlashCommands\":[\"plan\",\"ask\"]"));
        assertTrue(json.contains("\"businessSessionDomain\":\"im\""));
    }

    @Test
    @DisplayName("6 参 / bizRobotTag 非空 → 写入 TextNode")
    void build6_bizRobotTagNonBlank_writesTextNode() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1", "robot-tag-1", null);

        assertEquals(4, node.size());
        assertEquals("robot-tag-1", node.get("bizRobotTag").asText());
    }

    @Test
    @DisplayName("6 参 / bizRobotTag + allowedSlashCommands 同时写入")
    void build6_bizRobotTagAndAllowedSlashCommands() {
        ObjectNode node = PlatformExtParamBuilder.build(
                objectMapper, "im", "group", "wx-g-1", "robot-tag-1", List.of("plan"));

        assertEquals(5, node.size());
        assertEquals("robot-tag-1", node.get("bizRobotTag").asText());
        assertEquals("plan", node.get("allowedSlashCommands").get(0).asText());
    }
}
