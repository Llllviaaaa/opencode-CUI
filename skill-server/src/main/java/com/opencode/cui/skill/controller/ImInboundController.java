package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.ContextInjectionService;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImSessionManager;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.PayloadBuilder;
import com.opencode.cui.skill.service.SkillMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inbound")
public class ImInboundController {

    private final AssistantAccountResolverService resolverService;
    private final ImSessionManager sessionManager;
    private final ContextInjectionService contextInjectionService;
    private final GatewayRelayService gatewayRelayService;
    private final SkillMessageService messageService;
    private final MessagePersistenceService messagePersistenceService;
    private final ObjectMapper objectMapper;

    public ImInboundController(
            AssistantAccountResolverService resolverService,
            ImSessionManager sessionManager,
            ContextInjectionService contextInjectionService,
            GatewayRelayService gatewayRelayService,
            SkillMessageService messageService,
            MessagePersistenceService messagePersistenceService,
            ObjectMapper objectMapper) {
        this.resolverService = resolverService;
        this.sessionManager = sessionManager;
        this.contextInjectionService = contextInjectionService;
        this.gatewayRelayService = gatewayRelayService;
        this.messageService = messageService;
        this.messagePersistenceService = messagePersistenceService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<Void>> receiveMessage(@RequestBody ImMessageRequest request) {
        log.info("Received IM inbound message: domain={}, sessionType={}, sessionId={}, assistant={}, msgType={}",
                request != null ? request.businessDomain() : null,
                request != null ? request.sessionType() : null,
                request != null ? request.sessionId() : null,
                request != null ? request.assistantAccount() : null,
                request != null ? request.msgType() : null);

        String validationError = validate(request);
        if (validationError != null) {
            log.warn("IM inbound validation failed: error={}, sessionId={}",
                    validationError, request != null ? request.sessionId() : null);
            return ResponseEntity.badRequest().body(ApiResponse.error(400, validationError));
        }

        AssistantResolveResult resolveResult = resolverService.resolve(request.assistantAccount());
        if (resolveResult == null) {
            log.warn("Failed to resolve assistant account: assistantAccount={}", request.assistantAccount());
            return ResponseEntity.ok(ApiResponse.error(404, "Invalid assistant account"));
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();
        log.info("Resolved assistant account: assistantAccount={}, ak={}, ownerWelinkId={}",
                request.assistantAccount(), ak, ownerWelinkId);

        String prompt = contextInjectionService.resolvePrompt(
                request.sessionType(),
                request.content(),
                request.chatHistory());
        log.debug("Context injection resolved: sessionType={}, promptLength={}",
                request.sessionType(), prompt != null ? prompt.length() : 0);

        SkillSession session = sessionManager.findSession(
                request.businessDomain(),
                request.sessionType(),
                request.sessionId(),
                ak);

        if (session == null) {
            log.info("No existing session found, creating async: domain={}, sessionType={}, sessionId={}, ak={}",
                    request.businessDomain(), request.sessionType(), request.sessionId(), ak);
            sessionManager.createSessionAsync(
                    request.businessDomain(),
                    request.sessionType(),
                    request.sessionId(),
                    ak,
                    ownerWelinkId,
                    request.assistantAccount(),
                    prompt);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            log.info("Session exists but toolSessionId not ready, requesting rebuild: skillSessionId={}",
                    session.getId());
            sessionManager.requestToolSession(session, prompt);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }

        log.info("Session ready, forwarding to gateway: skillSessionId={}, toolSessionId={}, sessionType={}",
                session.getId(), session.getToolSessionId(), request.sessionType());

        if (session.isImDirectSession()) {
            log.debug("Direct session: persisting user message turn, skillSessionId={}", session.getId());
            messagePersistenceService.finalizeActiveAssistantTurn(session.getId());
            messageService.saveUserMessage(session.getId(), request.content());
            messagePersistenceService.markPendingUserMessage(session.getId());
        }

        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("text", prompt);
        payloadFields.put("toolSessionId", session.getToolSessionId());
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                session.getAk(),
                ownerWelinkId,
                String.valueOf(session.getId()),
                GatewayActions.CHAT,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        log.info("Gateway invoke sent: skillSessionId={}, ak={}, action={}",
                session.getId(), ak, GatewayActions.CHAT);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private String validate(ImMessageRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.businessDomain() == null || request.businessDomain().isBlank()) {
            return "businessDomain is required";
        }
        if (!SkillSession.DOMAIN_IM.equalsIgnoreCase(request.businessDomain())) {
            return "Only IM inbound is supported";
        }
        if (request.sessionType() == null || request.sessionType().isBlank()) {
            return "sessionType is required";
        }
        if (!SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(request.sessionType())
                && !SkillSession.SESSION_TYPE_DIRECT.equalsIgnoreCase(request.sessionType())) {
            return "Invalid sessionType";
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return "sessionId is required";
        }
        if (request.assistantAccount() == null || request.assistantAccount().isBlank()) {
            return "assistantAccount is required";
        }
        if (request.content() == null || request.content().isBlank()) {
            return "content is required";
        }
        if (!request.isTextMessage()) {
            return "Only text messages are supported";
        }
        return null;
    }
}
