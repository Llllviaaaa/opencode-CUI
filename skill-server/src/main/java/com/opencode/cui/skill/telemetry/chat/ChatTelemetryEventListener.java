package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.telemetry.core.TelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat 埋码事件 listener：把 {@link ChatRequestTelemetryEvent} / {@link ChatReplyTelemetryEvent}
 * 翻译成 {@link TelemetryEvent} 调 {@link WelinkTelemetryReporter#report(TelemetryEvent)}。
 *
 * <p>仅在 {@code telemetry.welink.enabled=true} 时装载；与 reporter / aspect 同步生效。
 *
 * <p>核心不变量：任何 listener 内部异常（DB 查询失败 / 字段缺失 / NPE）
 * 都被顶层 try-catch 兜底，不抛回业务线程。
 *
 * <p>字段映射（PRD §3）：
 * <ul>
 *   <li>request 事件：{@code eventId=skill_chat_request, eventLabel=用户发起 chat 对话,
 *       userId=senderUserAccount}，extendData 含 6 个业务字段</li>
 *   <li>reply 事件：{@code eventId=skill_chat_response, eventLabel=助手回复 chat 对话,
 *       userId=assistantAccount}，extendData 同样含 6 个业务字段</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class ChatTelemetryEventListener {

    private static final String EVENT_ID_REQUEST = "skill_chat_request";
    private static final String EVENT_ID_REPLY = "skill_chat_response";
    private static final String EVENT_LABEL_REQUEST = "用户发起 chat 对话";
    private static final String EVENT_LABEL_REPLY = "助手回复 chat 对话";

    private final WelinkTelemetryReporter reporter;
    private final SkillSessionRepository sessionRepository;
    private final AssistantInfoService assistantInfoService;

    public ChatTelemetryEventListener(WelinkTelemetryReporter reporter,
                                      SkillSessionRepository sessionRepository,
                                      AssistantInfoService assistantInfoService) {
        this.reporter = reporter;
        this.sessionRepository = sessionRepository;
        this.assistantInfoService = assistantInfoService;
    }

    @EventListener
    public void onChatRequest(ChatRequestTelemetryEvent event) {
        try {
            if (event == null || event.session() == null) {
                return;
            }
            SkillSession session = event.session();
            String senderUserAccount = event.senderUserAccount();
            if (senderUserAccount == null || senderUserAccount.isBlank()) {
                log.warn("[WelinkTelemetry] skip chat_request: reason=blank_senderUserAccount, sessionId={}",
                        session.getId());
                return;
            }
            String businessSessionId = session.getBusinessSessionId();
            String assistantAccount = session.getAssistantAccount();
            Map<String, Object> extendData = buildExtendData(
                    session.getBusinessSessionDomain(),
                    session.getBusinessSessionType(),
                    businessSessionId,
                    senderUserAccount,
                    assistantAccount,
                    event.businessTag());
            reporter.report(simpleEvent(
                    EVENT_ID_REQUEST,
                    EVENT_LABEL_REQUEST,
                    businessSessionId,
                    senderUserAccount,
                    extendData));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] onChatRequest failed: error={}", t.getMessage());
        }
    }

    @EventListener
    public void onChatReply(ChatReplyTelemetryEvent event) {
        try {
            if (event == null || event.sessionId() == null) {
                return;
            }
            SkillSession session = sessionRepository.findById(event.sessionId());
            if (session == null) {
                log.warn("[WelinkTelemetry] skip chat_reply: reason=session_not_found, sessionId={}",
                        event.sessionId());
                return;
            }
            String assistantAccount = session.getAssistantAccount();
            if (assistantAccount == null || assistantAccount.isBlank()) {
                log.warn("[WelinkTelemetry] skip chat_reply: reason=blank_assistantAccount, sessionId={}",
                        session.getId());
                return;
            }
            String businessSessionId = session.getBusinessSessionId();
            String businessTag = resolveBusinessTag(session.getAk());
            Map<String, Object> extendData = buildExtendData(
                    session.getBusinessSessionDomain(),
                    session.getBusinessSessionType(),
                    businessSessionId,
                    session.getUserId(),
                    assistantAccount,
                    businessTag);
            reporter.report(simpleEvent(
                    EVENT_ID_REPLY,
                    EVENT_LABEL_REPLY,
                    businessSessionId,
                    assistantAccount,
                    extendData));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] onChatReply failed: sessionId={}, error={}",
                    event == null ? null : event.sessionId(), t.getMessage());
        }
    }

    /**
     * Reply 路径里 controller 局部变量 scopeInfo 不可见，需要按 ak 反查一次。
     * 上游故障时 {@link AssistantInfoService#getAssistantInfo} 返 null，按字段缺失处理（返 null businessTag）。
     */
    private String resolveBusinessTag(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        try {
            AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
            return info == null ? null : info.getBusinessTag();
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] resolveBusinessTag failed: ak={}, error={}", ak, t.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildExtendData(String businessSessionDomain,
                                                String businessSessionType,
                                                String businessSessionId,
                                                String senderUserAccount,
                                                String assistantAccount,
                                                String businessTag) {
        Map<String, Object> extendData = new LinkedHashMap<>();
        extendData.put("businessSessionDomain", nullToEmpty(businessSessionDomain));
        extendData.put("businessSessionType", nullToEmpty(businessSessionType));
        extendData.put("businessSessionId", nullToEmpty(businessSessionId));
        extendData.put("senderUserAccount", nullToEmpty(senderUserAccount));
        extendData.put("assistantAccount", nullToEmpty(assistantAccount));
        extendData.put("businessTag", nullToEmpty(businessTag));
        return extendData;
    }

    private static String nullToEmpty(Object v) {
        return v == null ? "" : v.toString();
    }

    private static TelemetryEvent simpleEvent(String eventId,
                                              String eventLabel,
                                              Object sessionIdObj,
                                              String userId,
                                              Map<String, Object> extendData) {
        String sessionId = sessionIdObj == null ? "" : sessionIdObj.toString();
        return new TelemetryEvent() {
            @Override public String eventId() { return eventId; }
            @Override public String eventLabel() { return eventLabel; }
            @Override public String sessionId() { return sessionId; }
            @Override public String userId() { return userId == null ? "" : userId; }
            @Override public Map<String, Object> extendData() { return extendData; }
        };
    }
}
