package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.ImPushRequest;
import com.opencode.cui.gateway.service.SkillRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CloudPushController 单元测试。
 *
 * <ul>
 *   <li>G22: 正常推送 - 验证构建的 GatewayMessage type=im_push, payload 含原始字段</li>
 *   <li>G23: topicId 路由 - 验证 GatewayMessage.toolSessionId = request.topicId</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudPushControllerTest {

    @Mock
    private SkillRelayService skillRelayService;

    private ObjectMapper objectMapper;

    @Captor
    private ArgumentCaptor<GatewayMessage> messageCaptor;

    private CloudPushController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new CloudPushController(skillRelayService, objectMapper);
    }

    @Test
    @DisplayName("G22: 正常推送 - type=im_push, payload 包含原始请求字段")
    void shouldBuildImPushMessageWithCorrectTypeAndPayload() {
        // given
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_001");
        request.setUserAccount("user_001");
        request.setImGroupId("group_001");
        request.setTopicId("topic_001");
        request.setContent("Hello, World!");

        when(skillRelayService.relayToSkill(any(GatewayMessage.class))).thenReturn(true);

        // when
        ResponseEntity<Void> response = controller.imPush(request);

        // then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillRelayService).relayToSkill(messageCaptor.capture());

        GatewayMessage captured = messageCaptor.getValue();
        assertEquals(GatewayMessage.Type.IM_PUSH, captured.getType());
        assertNotNull(captured.getTraceId(), "traceId should be auto-generated");
        assertNotNull(captured.getPayload(), "payload should not be null");

        // 验证 payload 包含原始请求字段
        assertEquals("bot_001", captured.getPayload().get("assistantAccount").asText());
        assertEquals("user_001", captured.getPayload().get("userAccount").asText());
        assertEquals("group_001", captured.getPayload().get("imGroupId").asText());
        assertEquals("topic_001", captured.getPayload().get("topicId").asText());
        assertEquals("Hello, World!", captured.getPayload().get("content").asText());
    }

    @Test
    @DisplayName("G23: topicId 路由 - toolSessionId 等于 request.topicId")
    void shouldSetToolSessionIdFromTopicId() {
        // given
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_002");
        request.setUserAccount("user_002");
        request.setTopicId("session_xyz_789");
        request.setContent("routing test");

        when(skillRelayService.relayToSkill(any(GatewayMessage.class))).thenReturn(true);

        // when
        controller.imPush(request);

        // then
        verify(skillRelayService).relayToSkill(messageCaptor.capture());

        GatewayMessage captured = messageCaptor.getValue();
        assertEquals("session_xyz_789", captured.getToolSessionId(),
                "toolSessionId should equal request.topicId for routing");
    }
}
