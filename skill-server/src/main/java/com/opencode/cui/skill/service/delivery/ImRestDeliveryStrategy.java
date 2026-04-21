package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ImRestDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ImOutboundService imOutboundService;

    public ImRestDeliveryStrategy(ImOutboundService imOutboundService) {
        this.imOutboundService = imOutboundService;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) return false;
        return session.isImDomain();
    }

    @Override
    public int order() { return 3; }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String text = buildImText(msg);
        if (text != null && !text.isBlank()) {
            imOutboundService.sendTextToIm(
                    session.getBusinessSessionType(),
                    session.getBusinessSessionId(),
                    text,
                    session.getAssistantAccount());
            log.info("[DELIVERY] ImRest: sessionId={}, type={}", sessionId,
                    msg != null ? msg.getType() : null);
        }
    }

    private String buildImText(StreamMessage msg) {
        if (msg == null) return null;
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            case StreamMessage.Types.PERMISSION_ASK ->
                    msg.getTitle() != null && !msg.getTitle().isBlank()
                            ? msg.getTitle() + "\n请回复: once / always / reject" : null;
            case StreamMessage.Types.QUESTION -> formatQuestionMessage(msg);
            default -> null;
        };
    }

    private String formatQuestionMessage(StreamMessage msg) {
        if (msg.getQuestionInfo() == null) return null;
        String status = msg.getStatus();
        if (status != null && !"running".equals(status) && !"pending".equals(status)) return null;
        StringBuilder text = new StringBuilder();
        if (msg.getQuestionInfo().getHeader() != null && !msg.getQuestionInfo().getHeader().isBlank()) {
            text.append(msg.getQuestionInfo().getHeader()).append('\n');
        }
        if (msg.getQuestionInfo().getQuestion() != null) {
            text.append(msg.getQuestionInfo().getQuestion());
        }
        if (msg.getQuestionInfo().getOptions() != null && !msg.getQuestionInfo().getOptions().isEmpty()) {
            text.append('\n');
            for (int i = 0; i < msg.getQuestionInfo().getOptions().size(); i++) {
                text.append(i + 1).append(". ").append(msg.getQuestionInfo().getOptions().get(i)).append('\n');
            }
        }
        return text.isEmpty() ? null : text.toString().trim();
    }
}
