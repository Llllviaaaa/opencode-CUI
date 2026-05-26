package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.DefaultAssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Session route flow for assistant-backed session lifecycle operations.
 */
@Slf4j
@Service
public class SkillSessionFlowService {

    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final AssistantAccountResolverService assistantAccountResolverService;
    private final DefaultAssistantRuleService ruleService;
    private final DefaultAssistantScopeStrategy defaultAssistantScopeStrategy;

    public SkillSessionFlowService(SkillSessionService sessionService,
                                   GatewayRelayService gatewayRelayService,
                                   ObjectMapper objectMapper,
                                   AssistantInfoService assistantInfoService,
                                   AssistantScopeDispatcher scopeDispatcher,
                                   AssistantAccountResolverService assistantAccountResolverService,
                                   DefaultAssistantRuleService ruleService,
                                   DefaultAssistantScopeStrategy defaultAssistantScopeStrategy) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.assistantAccountResolverService = assistantAccountResolverService;
        this.ruleService = ruleService;
        this.defaultAssistantScopeStrategy = defaultAssistantScopeStrategy;
    }

    public ApiResponse<SkillSession> createSession(String resolvedUserId,
                                                   String userIdCookie,
                                                   CreateSessionCommand request) {
        boolean hasExplicit = hasAssistantIdentity(request.ak(), request.assistantAccount());
        if (!hasExplicit) {
            Optional<DefaultAssistantRule> ruleOpt = ruleService.lookup(
                    request.businessSessionDomain(), request.businessSessionType());
            if (ruleOpt.isEmpty()) {
                log.warn("[BLOCK] createSession: reason=missing_ak_and_no_rule, domain={}, type={}, userId={}",
                        request.businessSessionDomain(), request.businessSessionType(), userIdCookie);
                return ApiResponse.error(400, "ak 和 assistantAccount 必填");
            }
            DefaultAssistantRule rule = ruleOpt.get();
            String toolSessionId = defaultAssistantScopeStrategy.generateToolSessionId();
            SkillSession injected = sessionService.createSessionWithDefaultAssistant(
                    resolvedUserId,
                    rule.ak(),
                    rule.assistantAccount(),
                    request.title(),
                    request.businessSessionDomain(),
                    request.businessSessionType(),
                    request.businessSessionId(),
                    toolSessionId);
            log.info("[INFO] createSession: rule-injected, domain={}, type={}, ak={}",
                    request.businessSessionDomain(), request.businessSessionType(), rule.ak());
            return ApiResponse.ok(injected);
        }

        ApiResponse<SkillSession> deletionBlock = checkAssistantDeletion(request.assistantAccount(), userIdCookie);
        if (deletionBlock != null) {
            return deletionBlock;
        }

        SkillSession session = sessionService.createSession(
                resolvedUserId,
                request.ak(),
                request.title(),
                request.businessSessionDomain(),
                request.businessSessionType(),
                request.businessSessionId(),
                request.assistantAccount());

        routeCreateSession(resolvedUserId, session, request);
        return ApiResponse.ok(session);
    }

    public void closeSession(SkillSession session) {
        if (shouldSendLifecycleInvoke(session)) {
            gatewayRelayService.sendInvokeToGateway(lifecycleCommand(session, GatewayActions.CLOSE_SESSION));
        }
        sessionService.closeSession(session.getId());
    }

    public void abortSession(SkillSession session) {
        if (shouldSendAbortInvoke(session)) {
            gatewayRelayService.sendInvokeToGateway(lifecycleCommand(session, GatewayActions.ABORT_SESSION));
        }
    }

    private void routeCreateSession(String resolvedUserId, SkillSession session, CreateSessionCommand request) {
        if (!hasAssistantIdentity(request.ak(), request.assistantAccount())) {
            return;
        }
        AssistantInfo info = getAssistantInfo(request.ak(), request.assistantAccount());
        AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
                session.getBusinessSessionDomain(), session.getBusinessSessionType(), info);
        String generatedToolSessionId = strategy.generateToolSessionId();
        if (generatedToolSessionId != null) {
            sessionService.updateToolSessionId(session.getId(), generatedToolSessionId);
            log.info("Business assistant: toolSessionId pre-generated, sessionId={}, toolSessionId={}",
                    session.getId(), generatedToolSessionId);
            return;
        }
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(request.ak(),
                        resolvedUserId,
                        session.getId().toString(),
                        GatewayActions.CREATE_SESSION,
                        PayloadBuilder.buildPayload(objectMapper,
                                request.title() != null && !request.title().isBlank()
                                        ? Map.of("title", request.title())
                                        : Map.of()),
                        null,
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        null,
                        session.getAssistantAccount(),
                        session.getAssistantAccount()));
    }

    private ApiResponse<SkillSession> checkAssistantDeletion(String assistantAccount, String userIdCookie) {
        if (assistantAccount == null || assistantAccount.isBlank()) {
            if (!assistantAccountResolverService.isSkipOnNullAssistantAccount()) {
                log.warn("[BLOCK] createSession: reason=no_assistant_account, decision=block, userId={}",
                        userIdCookie);
                return ApiResponse.error(400, "assistantAccount is required");
            }
            log.info("[SKIP] createSession: reason=no_assistant_account, decision=allow, userId={}", userIdCookie);
            return null;
        }
        ExistenceStatus status = assistantAccountResolverService.check(assistantAccount);
        if (status == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] createSession: reason=assistant_not_exists, decision=block, assistantAccount={}, userId={}",
                    assistantAccount, userIdCookie);
            return ApiResponse.error(410, assistantAccountResolverService.getDeletionMessage());
        }
        if (status == ExistenceStatus.UNKNOWN) {
            log.warn("[WARN] createSession: reason=assistant_check_unknown, decision=allow-unknown, assistantAccount={}, userId={}",
                    assistantAccount, userIdCookie);
        }
        return null;
    }

    private boolean shouldSendLifecycleInvoke(SkillSession session) {
        boolean isDefaultAssistant = ruleService.lookup(
                session.getBusinessSessionDomain(), session.getBusinessSessionType()).isPresent();
        return !isDefaultAssistant
                && hasAssistantIdentity(session.getAk(), session.getAssistantAccount())
                && session.getToolSessionId() != null;
    }

    private boolean shouldSendAbortInvoke(SkillSession session) {
        return hasAssistantIdentity(session.getAk(), session.getAssistantAccount())
                && session.getToolSessionId() != null;
    }

    private InvokeCommand lifecycleCommand(SkillSession session, String action) {
        return new InvokeCommand(session.getAk(),
                session.getUserId(),
                session.getId().toString(),
                action,
                PayloadBuilder.buildPayload(objectMapper, Map.of("toolSessionId", session.getToolSessionId())),
                null,
                session.getBusinessSessionDomain(),
                session.getBusinessSessionType(),
                session.getBusinessSessionId(),
                null,
                session.getAssistantAccount(),
                session.getAssistantAccount());
    }

    private boolean hasAssistantIdentity(String ak, String assistantAccount) {
        return (ak != null && !ak.isBlank())
                || (assistantAccount != null && !assistantAccount.isBlank());
    }

    private AssistantInfo getAssistantInfo(String ak, String assistantAccount) {
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            return assistantInfoService.getAssistantInfo(ak, assistantAccount);
        }
        return assistantInfoService.getAssistantInfo(ak);
    }

    public record CreateSessionCommand(String ak,
                                       String title,
                                       String businessSessionDomain,
                                       String businessSessionType,
                                       String businessSessionId,
                                       String assistantAccount) {
    }
}
