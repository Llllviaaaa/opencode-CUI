package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantSquareCloudRequestStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantSquareCloudRequestStrategy strategy = new AssistantSquareCloudRequestStrategy(objectMapper);

    @Test
    void name_isAssistantSquare() {
        assertThat(strategy.getName()).isEqualTo("assistant_square");
    }

    @Test
    void build_mapsAssistantSquareFields() {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("foo", "bar");
        CloudRequestContext ctx = CloudRequestContext.builder()
                .content("hello")
                .assistantAccount("dig_30051824")
                .sendUserAccount("u-001")
                .imGroupId("g-1")
                .clientLang("en")
                .topicId("1234567890123456789")
                .extParameters(ext)
                .build();

        ObjectNode out = strategy.build(ctx);

        // assistantAccount String 直传
        assertThat(out.path("assistantAccount").asText()).isEqualTo("dig_30051824");
        assertThat(out.path("sendW3Account").asText()).isEqualTo("u-001");
        assertThat(out.path("msgBody").asText()).isEqualTo("hello");
        assertThat(out.path("clientLang").asText()).isEqualTo("en");
        assertThat(out.path("imGroupId").asText()).isEqualTo("g-1");
        // topicId Long.parseLong
        assertThat(out.path("topicId").isIntegralNumber()).isTrue();
        assertThat(out.path("topicId").asLong()).isEqualTo(1234567890123456789L);
        // extParameters 透传
        assertThat(out.path("extParameters").path("foo").asText()).isEqualTo("bar");
    }

    @Test
    void build_clientLang_defaultsZh() {
        CloudRequestContext ctx = CloudRequestContext.builder()
                .content("hi")
                .assistantAccount("a")
                .sendUserAccount("u")
                .topicId("1")
                .build();
        ObjectNode out = strategy.build(ctx);
        assertThat(out.path("clientLang").asText()).isEqualTo("zh");
    }

    @Test
    void build_extParameters_nullOrEmpty_emitsEmptyObject() {
        CloudRequestContext ctx = CloudRequestContext.builder()
                .content("hi")
                .assistantAccount("a")
                .sendUserAccount("u")
                .topicId("1")
                .build();
        ObjectNode out = strategy.build(ctx);
        assertThat(out.path("extParameters").isObject()).isTrue();
        assertThat(out.path("extParameters").size()).isEqualTo(0);
    }

    @Test
    void build_blankAssistantAccount_throws() {
        CloudRequestContext ctx = CloudRequestContext.builder()
                .assistantAccount("")
                .sendUserAccount("u")
                .topicId("1")
                .build();
        assertThatThrownBy(() -> strategy.build(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistantAccount");
    }

    @Test
    void build_blankSendUserAccount_throws() {
        CloudRequestContext ctx = CloudRequestContext.builder()
                .assistantAccount("a")
                .sendUserAccount(" ")
                .topicId("1")
                .build();
        assertThatThrownBy(() -> strategy.build(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sendUserAccount");
    }

    @Test
    void build_nonNumericTopicId_throws() {
        // 旧 cloud-xxx 格式 → parseLong 失败 → fast-fail
        CloudRequestContext ctx = CloudRequestContext.builder()
                .assistantAccount("a")
                .sendUserAccount("u")
                .topicId("cloud-abc123")
                .build();
        assertThatThrownBy(() -> strategy.build(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topicId");
    }
}
