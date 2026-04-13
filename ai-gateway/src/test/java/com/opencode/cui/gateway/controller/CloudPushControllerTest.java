package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.ImPushRequest;
import com.opencode.cui.gateway.service.AssistantAccountResolver;
import com.opencode.cui.gateway.service.AssistantAccountResolver.ResolveResult;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudPushControllerTest {

    @Mock
    private SkillRelayService skillRelayService;

    @Mock
    private AssistantAccountResolver assistantAccountResolver;

    private ObjectMapper objectMapper;

    @Captor
    private ArgumentCaptor<GatewayMessage> messageCaptor;

    private CloudPushController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new CloudPushController(skillRelayService, objectMapper, assistantAccountResolver);
    }

    @Test
    @DisplayName("G22: 群聊推送 - type=im_push, payload 包含原始请求字段")
    void shouldBuildImPushMessageWithCorrectTypeAndPayload() {
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_001");
        request.setUserAccount("user_001");
        request.setImGroupId("group_001");  // 群聊不校验 userAccount
        request.setTopicId("topic_001");
        request.setContent("Hello, World!");

        when(assistantAccountResolver.resolve("bot_001"))
                .thenReturn(createResult("ak_001", "creator_001"));
        when(skillRelayService.relayToSkill(any(GatewayMessage.class))).thenReturn(true);

        ResponseEntity<?> response = controller.imPush(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillRelayService).relayToSkill(messageCaptor.capture());

        GatewayMessage captured = messageCaptor.getValue();
        assertEquals(GatewayMessage.Type.IM_PUSH, captured.getType());
        assertNotNull(captured.getTraceId());
        assertEquals("bot_001", captured.getPayload().get("assistantAccount").asText());
        assertEquals("Hello, World!", captured.getPayload().get("content").asText());
    }

    @Test
    @DisplayName("G23: topicId 路由 - toolSessionId 等于 request.topicId")
    void shouldSetToolSessionIdFromTopicId() {
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_002");
        request.setUserAccount("creator_002");  // 单聊需匹配 create_by
        request.setTopicId("session_xyz_789");
        request.setContent("routing test");

        when(assistantAccountResolver.resolve("bot_002"))
                .thenReturn(createResult("ak_002", "creator_002"));
        when(skillRelayService.relayToSkill(any(GatewayMessage.class))).thenReturn(true);

        controller.imPush(request);

        verify(skillRelayService).relayToSkill(messageCaptor.capture());
        assertEquals("session_xyz_789", messageCaptor.getValue().getToolSessionId());
    }

    @Test
    @DisplayName("校验：无效 assistantAccount 返回 400")
    void shouldReject400WhenAssistantAccountInvalid() {
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("invalid");
        request.setContent("test");

        when(assistantAccountResolver.resolve("invalid")).thenReturn(null);

        ResponseEntity<?> response = controller.imPush(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(skillRelayService, never()).relayToSkill(any());
    }

    @Test
    @DisplayName("校验：单聊 userAccount 不匹配 create_by 返回 403")
    void shouldReject403WhenUserAccountNotCreator() {
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_003");
        request.setUserAccount("wrong_user");
        request.setContent("test");

        when(assistantAccountResolver.resolve("bot_003"))
                .thenReturn(createResult("ak_003", "real_creator"));

        ResponseEntity<?> response = controller.imPush(request);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(skillRelayService, never()).relayToSkill(any());
    }

    @Test
    @DisplayName("校验：content 为空返回 400")
    void shouldReject400WhenContentBlank() {
        ImPushRequest request = new ImPushRequest();
        request.setAssistantAccount("bot_004");
        request.setContent("");

        ResponseEntity<?> response = controller.imPush(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(skillRelayService, never()).relayToSkill(any());
    }

    private static ResolveResult createResult(String ak, String createBy) {
        ResolveResult r = new ResolveResult();
        r.setAk(ak);
        r.setCreateBy(createBy);
        return r;
    }
}
