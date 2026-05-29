package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CloudRequestBuilder 单元测试。
 * 验证策略路由逻辑和 DefaultCloudRequestStrategy 的 JSON 构建正确性。
 */
@ExtendWith(MockitoExtension.class)
class CloudRequestBuilderTest {

    @Mock
    private SysConfigService sysConfigService;

    private ObjectMapper objectMapper;
    private DefaultCloudRequestStrategy defaultStrategy;
    private CloudRequestBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        defaultStrategy = new DefaultCloudRequestStrategy(objectMapper);
        builder = new CloudRequestBuilder(List.of(defaultStrategy), sysConfigService);
    }

    // ------------------------------------------------------------------ 策略路由

    @Nested
    @DisplayName("策略路由")
    class StrategyRouting {

        @Test
        @DisplayName("未配置 appId 时（SysConfig 返回 null）走默认策略")
        void usesDefaultStrategyWhenNoConfigFound() {
            when(sysConfigService.getValue(CloudRequestBuilder.CONFIG_TYPE, "app-001")).thenReturn(null);

            CloudRequestContext context = buildMinimalContext();
            ObjectNode result = builder.buildCloudRequest("app-001", context);

            assertNotNull(result);
            // 默认策略 type 应为 "text"
            assertEquals("text", result.get("type").asText());
            verify(sysConfigService).getValue(CloudRequestBuilder.CONFIG_TYPE, "app-001");
        }

        @Test
        @DisplayName("配置了 appId 且策略存在时走对应策略")
        void usesConfiguredStrategyWhenFound() {
            // 自定义策略 stub
            CloudRequestStrategy customStrategy = mock(CloudRequestStrategy.class);
            when(customStrategy.getName()).thenReturn("custom");
            ObjectNode customNode = objectMapper.createObjectNode();
            customNode.put("custom", true);
            when(customStrategy.build(any())).thenReturn(customNode);

            CloudRequestBuilder builderWithCustom = new CloudRequestBuilder(
                    List.of(defaultStrategy, customStrategy), sysConfigService);

            when(sysConfigService.getValue(CloudRequestBuilder.CONFIG_TYPE, "app-002")).thenReturn("custom");

            CloudRequestContext context = buildMinimalContext();
            ObjectNode result = builderWithCustom.buildCloudRequest("app-002", context);

            assertTrue(result.get("custom").asBoolean());
            verify(customStrategy).build(context);
        }

        @Test
        @DisplayName("配置的策略名不存在时走默认策略")
        void fallsBackToDefaultWhenStrategyNameNotFound() {
            when(sysConfigService.getValue(CloudRequestBuilder.CONFIG_TYPE, "app-003"))
                    .thenReturn("nonexistent-strategy");

            CloudRequestContext context = buildMinimalContext();
            ObjectNode result = builder.buildCloudRequest("app-003", context);

            assertNotNull(result);
            // 默认策略 type 应为 "text"
            assertEquals("text", result.get("type").asText());
        }
    }

    // ------------------------------------------------------------------ DefaultCloudRequestStrategy

    @Nested
    @DisplayName("DefaultCloudRequestStrategy JSON 构建")
    class DefaultStrategyBuild {

        @Test
        @DisplayName("构建正确的 JSON 结构，所有字段直接映射")
        void buildsCorrectJsonStructure() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("hello world")
                    .contentType("text")
                    .assistantAccount("asst-001")
                    .sendUserAccount("user-001")
                    .imGroupId("group-001")
                    .clientLang("zh")
                    .clientType("asst-pc")
                    .topicId("topic-001")
                    .messageId("msg-001")
                    .extParameters(Map.of("key1", "val1"))
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertEquals("text", result.get("type").asText());
            assertEquals("hello world", result.get("content").asText());
            assertEquals("asst-001", result.get("assistantAccount").asText());
            assertEquals("user-001", result.get("sendUserAccount").asText());
            assertEquals("group-001", result.get("imGroupId").asText());
            assertEquals("zh", result.get("clientLang").asText());
            assertEquals("asst-pc", result.get("clientType").asText());
            assertEquals("topic-001", result.get("topicId").asText());
            assertEquals("msg-001", result.get("messageId").asText());
            assertTrue(result.get("extParameters").has("key1"));
            assertEquals("val1", result.get("extParameters").get("key1").asText());
        }

        @Test
        @DisplayName("contentType 为 null 时 type 默认为 'text'")
        void defaultsTypeToTextWhenContentTypeIsNull() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("test")
                    .contentType(null)
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertEquals("text", result.get("type").asText());
        }

        @Test
        @DisplayName("clientLang 为 null 时默认为 'zh'")
        void defaultsClientLangToZhWhenNull() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("test")
                    .clientLang(null)
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertEquals("zh", result.get("clientLang").asText());
        }

        @Test
        @DisplayName("extParameters 为 null 时设为空对象")
        void setsEmptyObjectWhenExtParametersIsNull() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("test")
                    .extParameters(null)
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertNotNull(result.get("extParameters"));
            assertTrue(result.get("extParameters").isObject());
            assertEquals(0, result.get("extParameters").size());
        }

        @Test
        @DisplayName("extParameters 为空 Map 时设为空对象")
        void setsEmptyObjectWhenExtParametersIsEmpty() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("test")
                    .extParameters(Map.of())
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertNotNull(result.get("extParameters"));
            assertTrue(result.get("extParameters").isObject());
            assertEquals(0, result.get("extParameters").size());
        }

        @Test
        @DisplayName("contentType 为 IMAGE-V1 时 type 正确映射")
        void mapsImageContentType() {
            CloudRequestContext context = CloudRequestContext.builder()
                    .content("base64data")
                    .contentType("IMAGE-V1")
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertEquals("IMAGE-V1", result.get("type").asText());
        }

        @Test
        @DisplayName("question_reply：写入 replyContext 嵌套对象（type/toolCallId/answers）")
        void buildCloudRequest_questionReply_writesReplyContext() {
            CloudRequestContext ctx = CloudRequestContext.builder()
                    .content("").contentType("text").topicId("ts-1")
                    .assistantAccount("asst-x").sendUserAccount("user-x")
                    .replyToolCallId("call-q")
                    .replyAnswers(List.of(List.of("A"), List.of("B", "C")))
                    .build();

            ObjectNode result = defaultStrategy.build(ctx);

            com.fasterxml.jackson.databind.JsonNode rc = result.path("replyContext");
            assertEquals("question_reply", rc.path("type").asText());
            assertEquals("call-q", rc.path("toolCallId").asText());
            assertTrue(rc.path("answers").isArray());
            assertEquals("A", rc.path("answers").get(0).get(0).asText());
            assertEquals("C", rc.path("answers").get(1).get(1).asText());
        }

        @Test
        @DisplayName("permission_reply：写入 replyContext 嵌套对象（type/permissionId/response）")
        void buildCloudRequest_permissionReply_writesReplyContext() {
            CloudRequestContext ctx = CloudRequestContext.builder()
                    .topicId("ts-1")
                    .assistantAccount("asst-x").sendUserAccount("user-x")
                    .replyPermissionId("perm-1")
                    .replyResponse("once")
                    .build();

            ObjectNode result = defaultStrategy.build(ctx);

            com.fasterxml.jackson.databind.JsonNode rc = result.path("replyContext");
            assertEquals("permission_reply", rc.path("type").asText());
            assertEquals("perm-1", rc.path("permissionId").asText());
            assertEquals("once", rc.path("response").asText());
        }

        @Test
        @DisplayName("chat：cloudRequest 不含 replyContext 字段")
        void buildCloudRequest_chat_noReplyContext() {
            CloudRequestContext ctx = CloudRequestContext.builder()
                    .content("hi").contentType("text").topicId("ts-1")
                    .assistantAccount("asst-x").sendUserAccount("user-x").build();

            ObjectNode result = defaultStrategy.build(ctx);

            assertFalse(result.has("replyContext"));
            assertEquals("hi", result.path("content").asText());
        }

        @Test
        @DisplayName("extParameters 含 businessExtParam/platformExtParam 嵌套结构正确序列化")
        void buildsNestedExtParametersStructure() {
            ObjectNode bepNode = objectMapper.createObjectNode();
            bepNode.put("isHwEmployee", false);
            bepNode.set("knowledgeId", objectMapper.createArrayNode().add("kb-1"));

            java.util.Map<String, Object> ext = new java.util.LinkedHashMap<>();
            ext.put("businessExtParam", bepNode);
            ext.put("platformExtParam", objectMapper.createObjectNode());

            CloudRequestContext context = CloudRequestContext.builder()
                    .content("hi")
                    .contentType("text")
                    .extParameters(ext)
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertNotNull(result.get("extParameters"));
            assertTrue(result.get("extParameters").isObject());
            assertTrue(result.get("extParameters").get("businessExtParam").isObject());
            assertEquals(false, result.get("extParameters").get("businessExtParam").get("isHwEmployee").asBoolean());
            assertTrue(result.get("extParameters").get("businessExtParam").get("knowledgeId").isArray());
            assertEquals("kb-1", result.get("extParameters").get("businessExtParam").get("knowledgeId").get(0).asText());
            assertTrue(result.get("extParameters").get("platformExtParam").isObject());
            assertEquals(0, result.get("extParameters").get("platformExtParam").size());
        }

        @Test
        @DisplayName("Default protocol preserves existing JSON string extParameters")
        void preservesJsonStringExtParameters() {
            java.util.Map<String, Object> ext = new java.util.LinkedHashMap<>();
            ext.put("businessExtParam", "{\"isHwEmployee\":false}");
            ext.put("platformExtParam", "{\"businessSessionId\":\"sid-1\"}");

            CloudRequestContext context = CloudRequestContext.builder()
                    .content("hi")
                    .contentType("text")
                    .extParameters(ext)
                    .assistantAccount("asst-x")
                    .sendUserAccount("user-x")
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertTrue(result.get("extParameters").get("businessExtParam").isTextual());
            assertEquals("{\"isHwEmployee\":false}",
                    result.get("extParameters").get("businessExtParam").asText());
            assertTrue(result.get("extParameters").get("platformExtParam").isTextual());
            assertEquals("{\"businessSessionId\":\"sid-1\"}",
                    result.get("extParameters").get("platformExtParam").asText());
        }
    }

    // ------------------------------------------------------------------ helper

    private CloudRequestContext buildMinimalContext() {
        return CloudRequestContext.builder()
                .content("test content")
                .contentType("text")
                .assistantAccount("asst-default")
                .sendUserAccount("user-default")
                .build();
    }

    // ------------------------------------------------------------------ fast-fail 校验

    @Nested
    @DisplayName("DefaultCloudRequestStrategy fast-fail 入口校验")
    class FastFailValidation {

        @Test
        @DisplayName("assistantAccount 为 null 时抛 IllegalArgumentException")
        void rejectsBlankAssistantAccount() {
            CloudRequestContext ctx = CloudRequestContext.builder()
                    .content("hi").sendUserAccount("user-x").build();

            assertThrows(IllegalArgumentException.class, () -> defaultStrategy.build(ctx));
        }

        @Test
        @DisplayName("sendUserAccount 为空白时抛 IllegalArgumentException")
        void rejectsBlankSendUserAccount() {
            CloudRequestContext ctx = CloudRequestContext.builder()
                    .content("hi").assistantAccount("asst-x").sendUserAccount("  ").build();

            assertThrows(IllegalArgumentException.class, () -> defaultStrategy.build(ctx));
        }
    }
}
