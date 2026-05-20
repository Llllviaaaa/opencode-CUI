package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.telemetry.core.TelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTelemetryEventListenerTest {

    private WelinkTelemetryReporter reporter;
    private SkillSessionRepository sessionRepository;
    private AssistantInfoService assistantInfoService;
    private ChatTelemetryEventListener listener;

    @BeforeEach
    void setUp() {
        reporter = mock(WelinkTelemetryReporter.class);
        sessionRepository = mock(SkillSessionRepository.class);
        assistantInfoService = mock(AssistantInfoService.class);
        listener = new ChatTelemetryEventListener(reporter, sessionRepository, assistantInfoService);
    }

    @Test
    @DisplayName("onChatRequest: eventId=skill_chat_request, userId=senderUserAccount, extendData 含 6 字段")
    void chatRequestMapping() {
        SkillSession session = SkillSession.builder()
                .id(123L)
                .userId("creator-A")
                .ak("AK-1")
                .assistantAccount("assist-X")
                .businessSessionDomain("miniapp")
                .businessSessionType("direct")
                .businessSessionId("biz-99")
                .build();

        listener.onChatRequest(new ChatRequestTelemetryEvent(session, "user-real", "tag-Z"));

        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(reporter).report(cap.capture());
        TelemetryEvent ev = cap.getValue();
        assertEquals("skill_chat_request", ev.eventId());
        assertEquals("用户发起 chat 对话", ev.eventLabel());
        assertEquals("biz-99", ev.sessionId());
        assertEquals("user-real", ev.userId());
        Map<String, Object> ext = ev.extendData();
        assertEquals("miniapp", ext.get("businessSessionDomain"));
        assertEquals("direct", ext.get("businessSessionType"));
        assertEquals("biz-99", ext.get("businessSessionId"));
        assertEquals("user-real", ext.get("senderUserAccount"));
        assertEquals("assist-X", ext.get("assistantAccount"));
        assertEquals("tag-Z", ext.get("businessTag"));
    }

    @Test
    @DisplayName("onChatRequest: senderUserAccount 空 → skip, reporter 不调")
    void chatRequestBlankSenderSkipped() {
        SkillSession session = SkillSession.builder().id(1L).build();
        listener.onChatRequest(new ChatRequestTelemetryEvent(session, "", "tag"));
        verify(reporter, never()).report(org.mockito.ArgumentMatchers.any());
        listener.onChatRequest(new ChatRequestTelemetryEvent(session, null, "tag"));
        verify(reporter, never()).report(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("onChatReply: 反查 session, eventId=skill_chat_response, userId=assistantAccount, businessTag 来自 AssistantInfo")
    void chatReplyMapping() {
        SkillSession session = SkillSession.builder()
                .id(7L)
                .userId("creator-A")
                .ak("AK-9")
                .assistantAccount("assist-Y")
                .businessSessionDomain("im")
                .businessSessionType("group")
                .businessSessionId("biz-77")
                .build();
        when(sessionRepository.findById(7L)).thenReturn(session);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("tag-from-info");
        when(assistantInfoService.getAssistantInfo("AK-9")).thenReturn(info);

        listener.onChatReply(new ChatReplyTelemetryEvent(7L));

        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(reporter).report(cap.capture());
        TelemetryEvent ev = cap.getValue();
        assertEquals("skill_chat_response", ev.eventId());
        assertEquals("助手回复 chat 对话", ev.eventLabel());
        assertEquals("biz-77", ev.sessionId());
        assertEquals("assist-Y", ev.userId());
        Map<String, Object> ext = ev.extendData();
        assertEquals("im", ext.get("businessSessionDomain"));
        assertEquals("group", ext.get("businessSessionType"));
        assertEquals("biz-77", ext.get("businessSessionId"));
        assertEquals("creator-A", ext.get("senderUserAccount"));
        assertEquals("assist-Y", ext.get("assistantAccount"));
        assertEquals("tag-from-info", ext.get("businessTag"));
    }

    @Test
    @DisplayName("onChatReply: session 不存在 → skip, reporter 不调")
    void chatReplySessionMissingSkipped() {
        when(sessionRepository.findById(404L)).thenReturn(null);
        listener.onChatReply(new ChatReplyTelemetryEvent(404L));
        verify(reporter, never()).report(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("onChatReply: assistantAccount 空 → skip, reporter 不调")
    void chatReplyBlankAssistantSkipped() {
        SkillSession session = SkillSession.builder().id(8L).ak("AK").build();
        // assistantAccount = null
        when(sessionRepository.findById(8L)).thenReturn(session);
        listener.onChatReply(new ChatReplyTelemetryEvent(8L));
        verify(reporter, never()).report(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("onChatReply: assistantInfoService 抛异常 → 兜底为 null businessTag, 仍上报")
    void chatReplyAssistantInfoFailureFallback() {
        SkillSession session = SkillSession.builder()
                .id(9L)
                .ak("AK")
                .assistantAccount("assist-Z")
                .businessSessionId("biz-9")
                .build();
        when(sessionRepository.findById(9L)).thenReturn(session);
        when(assistantInfoService.getAssistantInfo("AK")).thenThrow(new RuntimeException("upstream down"));

        listener.onChatReply(new ChatReplyTelemetryEvent(9L));

        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(reporter).report(cap.capture());
        assertEquals("", cap.getValue().extendData().get("businessTag"));
    }
}
