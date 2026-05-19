package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Ticker;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GatewayMessageRouter route_confirm 去重单元测试。
 *
 * <p>覆盖 PRD 04-24-skill-server-route-confirm-dedup 的 7 个测试用例：
 * <ol>
 *   <li>5min 内 100 条上行 → 仅 1 次 sendRouteConfirm</li>
 *   <li>TTL（25min）过期后再上行 → 重发 1 次</li>
 *   <li>toolSessionId remap 到新 welinkSessionId → 立即重发</li>
 *   <li>5min force-reconfirm（lease 到期）→ 触发 1 次 reconfirm</li>
 *   <li>sendRouteConfirm 返回 false → 不写 cache，下条上行重试</li>
 *   <li>DB 查询抛异常 → 不发 route_reject，仅 log</li>
 *   <li>enabled=false → 100 条上行 → 100 次 sendRouteConfirm</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GatewayMessageRouterTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String TOOL_SESSION_ID = "tool-session-001";
    private static final String WELINK_SESSION_ID = "42";
    private static final String WELINK_SESSION_ID_NEW = "99";
    private static final int CONFIRM_CACHE_EXPIRE_MINUTES = 25;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private OpenCodeEventTranslator translator;
    @Mock
    private MessagePersistenceService persistenceService;
    @Mock
    private StreamBufferService bufferService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private ImInteractionStateService interactionStateService;
    @Mock
    private ImOutboundService imOutboundService;
    @Mock
    private SessionRouteService sessionRouteService;
    @Mock
    private SkillInstanceRegistry skillInstanceRegistry;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private ChannelLookupService channelLookupService;
    @Mock
    private ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantScopeStrategy scopeStrategy;
    @Mock
    private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    @Mock
    GatewayMessageRouter.RouteResponseSender routeResponseSender;

    private MutableClock clock;
    private FakeTicker ticker;
    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
        // 让路由总是本地处理
        lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);
        // scope 默认放行（nullable 覆盖 getAssistantInfo 返回 null 的情况）
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(scopeStrategy);
        lenient().when(scopeStrategy.translateEvent(any(), any()))
                .thenAnswer(inv -> translator.translate(inv.getArgument(0)));
        lenient().when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hi")
                .build());

        clock = new MutableClock(Instant.parse("2026-04-24T00:00:00Z"));
        ticker = new FakeTicker();
    }

    private GatewayMessageRouter buildRouter(boolean dedupEnabled) {
        GatewayMessageRouter r = new GatewayMessageRouter(
                objectMapper,
                messageService,
                sessionService,
                redisMessageBroker,
                translator,
                persistenceService,
                bufferService,
                rebuildService,
                interactionStateService,
                imOutboundService,
                sessionRouteService,
                skillInstanceRegistry,
                assistantInfoService,
                channelLookupService,
                channelSuppressReplyWhitelistService,
                scopeDispatcher,
                outboundDeliveryDispatcher,
                emitter,
                120,
                dedupEnabled,
                CONFIRM_CACHE_EXPIRE_MINUTES,
                clock,
                ticker);
        r.initConfirmDedupCache();
        r.setRouteResponseSender(routeResponseSender);
        return r;
    }

    /** 构造一条 tool_event：仅带 toolSessionId（强制走 DB/Redis 反查路径以触发 confirm）。 */
    private ObjectNode buildToolEventByToolSession(String toolSessionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_event");
        node.put("toolSessionId", toolSessionId);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("data", "x");
        node.set("event", event);
        return node;
    }

    // ============= 用例 1：100 条上行 5min 内仅 1 次 confirm =============

    @Test
    @DisplayName("用例1: 同一 toolSessionId 5min 内 100 条上行仅触发 1 次 sendRouteConfirm")
    void case1_dedup100MessagesWithinLease() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        for (int i = 0; i < 100; i++) {
            router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        }

        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 2：TTL 过期（25min）后再上行 → 重发 1 次 =============

    @Test
    @DisplayName("用例2: cache TTL（25min）过期后下一条上行重发 1 次 confirm")
    void case2_reconfirmAfterCacheTtl() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 推进 ticker 25min + clock 25min（cache 失效 + lease 也 stale）
        ticker.advance(CONFIRM_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        clock.advanceMinutes(CONFIRM_CACHE_EXPIRE_MINUTES);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 3：toolSessionId remap 到新 welinkSessionId → 立即重发 =============

    @Test
    @DisplayName("用例3: 同一 toolSessionId 解析到新 welinkSessionId（remap）立即重发 confirm")
    void case3_remapTriggersImmediateReconfirm() {
        router = buildRouter(true);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        // 第 1 条：解析到 WELINK_SESSION_ID
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 2 条：同一 toolSessionId 解析到新的 WELINK_SESSION_ID_NEW（remap）
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID_NEW);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID_NEW));
    }

    // ============= 用例 4：5min force-reconfirm（lease 到期）触发重发 =============

    @Test
    @DisplayName("用例4: 5min lease 到期后下一条上行强制 reconfirm 1 次")
    void case4_forceReconfirmAfterLease() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 推进 5min（恰好到 lease 边界）—— 注意：cache TTL 25min 还未到，cache 仍命中，但 lease 已 stale
        clock.advanceMinutes(5);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 紧接着再来 1 条（lease 已被刷新）→ 不再发
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 5：sendRouteConfirm 返回 false → 不写 cache，下条重试 =============

    @Test
    @DisplayName("用例5: sendRouteConfirm 返回 false（GW 未连接）不写 cache，下条上行再次尝试")
    void case5_sendFailureDoesNotPoisonCache() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);

        // 第 1 条 send 失败
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(false);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 2 条 send 仍失败 → 又一次尝试（说明 cache 未污染）
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 3 条 send 成功 → 第 4 条应被 dedup
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(3)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(3)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 6：DB 查询抛异常 → 不发 route_reject =============

    @Test
    @DisplayName("用例6: DB 查询抛异常时不发 route_reject，仅 log")
    void case6_exceptionDoesNotTriggerRouteReject() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(null);
        when(sessionService.findByToolSessionId(TOOL_SESSION_ID))
                .thenThrow(new RuntimeException("DB connection failure"));

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));

        verify(routeResponseSender, never()).sendRouteReject(any());
        verify(routeResponseSender, never()).sendRouteConfirm(any(), any());
    }

    // ============= 用例 7：enabled=false → 每条上行都发 confirm =============

    @Test
    @DisplayName("用例7: dedup 关闭时 100 条上行触发 100 次 sendRouteConfirm（与改动前行为一致）")
    void case7_dedupDisabledSendsEveryTime() {
        router = buildRouter(false);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        for (int i = 0; i < 100; i++) {
            router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        }

        verify(routeResponseSender, times(100)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 边界：DB 命中（Redis miss）路径同样去重 =============

    @Test
    @DisplayName("边界: Redis miss + DB hit 路径也走去重逻辑")
    void edge_dbHitPathAlsoDedupes() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(null);
        SkillSession session = SkillSession.builder().id(42L).build();
        when(sessionService.findByToolSessionId(TOOL_SESSION_ID)).thenReturn(session);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        // 第 2 条改回 Redis 命中（同一 welinkSessionId）→ 应被 dedup
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));

        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= initSsRelaySubscription 必须延后到 ApplicationReadyEvent =============

    @Test
    @DisplayName("initSsRelaySubscription 仅在 ApplicationReadyEvent 触发后才调 broker.subscribeToSsRelay")
    void initSsRelaySubscription_onlyRegistersAfterApplicationReadyEvent() {
        // 构造期 + initConfirmDedupCache 不应触发任何 redisMessageBroker 调用
        // （注意 setUp 已 stub `getToolSessionMapping` 等，但仅在 router.route 时才调用）
        GatewayMessageRouter r = buildRouter(true);
        verifyNoInteractions(redisMessageBroker);

        // 模拟 Spring 在所有 bean 就绪后发的 ApplicationReadyEvent
        r.initSsRelaySubscription(mock(ApplicationReadyEvent.class));

        verify(redisMessageBroker, times(1)).subscribeToSsRelay(eq(LOCAL_INSTANCE), any());
    }

    // ============= tool_error 路由（reason 优先 + isSessionInvalidError 回退） =============

    /** 构造一条 tool_error：携带 welinkSessionId 直连，避免 toolSessionId 反查路径。 */
    private ObjectNode buildToolError(String welinkSessionId, String error, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_error");
        node.put("welinkSessionId", welinkSessionId);
        node.put("error", error);
        if (reason != null) {
            node.put("reason", reason);
        }
        return node;
    }

    @Test
    @DisplayName("tool_error reason=callback_config_missing 不触发 rebuild，走错误投递")
    void toolError_callbackConfigMissing_doesNotRebuild() {
        router = buildRouter(true);
        ObjectNode node = buildToolError(WELINK_SESSION_ID,
                "Cloud agent error: Cloud route info not found for ak: ak-xx",
                "callback_config_missing");

        router.route("tool_error", null, null, node);

        verify(rebuildService, never()).handleSessionNotFound(anyString(), any(), any());
        verify(messageService, times(1))
                .saveSystemMessage(eq(42L), org.mockito.ArgumentMatchers.contains("Cloud route info not found"));
        org.mockito.ArgumentCaptor<StreamMessage> msgCap = org.mockito.ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter).emitToSession(any(), eq(WELINK_SESSION_ID), any(), msgCap.capture());
        assertEquals(StreamMessage.Types.ERROR, msgCap.getValue().getType());
    }

    @Test
    @DisplayName("tool_error reason 缺失 + error 含 'session not found' → 触发 rebuild（保留旧行为）")
    void toolError_legacySessionNotFoundFallback_triggersRebuild() {
        router = buildRouter(true);
        ObjectNode node = buildToolError(WELINK_SESSION_ID,
                "Cloud agent error: Session not found for toolSessionId xyz",
                null);

        router.route("tool_error", null, null, node);

        verify(rebuildService, times(1))
                .handleSessionNotFound(eq(WELINK_SESSION_ID), any(), any());
    }

    @Test
    @DisplayName("tool_error reason 缺失 + error 含 'Cloud route info not found'（收紧后不命中）→ 不触发 rebuild")
    void toolError_cloudRouteNotFoundLegacy_doesNotTriggerRebuild() {
        router = buildRouter(true);
        // 老 GW 不发 reason；error 文案包含 "not found" 但不是 session 失效语义。
        // 收紧 isSessionInvalidError 后不再命中，避免误重建。
        ObjectNode node = buildToolError(WELINK_SESSION_ID,
                "Cloud agent error: Cloud route info not found for ak: ak-xx",
                null);

        router.route("tool_error", null, null, node);

        verify(rebuildService, never()).handleSessionNotFound(anyString(), any(), any());
    }

    // ============= Helpers: MutableClock + FakeTicker =============

    /** 测试专用可推进 Clock。 */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advanceMinutes(long minutes) {
            this.now = this.now.plusSeconds(minutes * 60L);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ==================== retryPendingMessages.suppressReply ====================

    @Test
    @DisplayName("retryPendingMessages: group session + channel hit whitelist -> all replays carry suppressReply=TRUE")
    void retryPendingMessages_groupSessionAndChannelInWhitelist_setsSuppressReplyOnAllReplays() {
        // Arrange
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "1001";
        String toolSessionId = "tool-1";
        String ak = "ak-1";
        String userId = "user-1";

        SkillSession groupSession = new SkillSession();
        groupSession.setId(1001L);
        groupSession.setBusinessSessionDomain("im");
        groupSession.setBusinessSessionType("group");
        groupSession.setBusinessSessionId("biz-group-001");
        when(sessionService.findByIdSafe(1001L)).thenReturn(groupSession);

        // PR2: retryPendingMessages 现在调 consumePendingRequests 返回 List<PendingChatRequest>
        com.opencode.cui.skill.model.PendingChatRequest req1 = new com.opencode.cui.skill.model.PendingChatRequest(
                "hello", "assist-1", "sender-real-1", "biz-group-001", "msg-1", null, "im", "group");
        com.opencode.cui.skill.model.PendingChatRequest req2 = new com.opencode.cui.skill.model.PendingChatRequest(
                "world", "assist-1", "sender-real-2", "biz-group-001", "msg-2", null, "im", "group");
        when(rebuildService.consumePendingRequests(welinkSessionId))
                .thenReturn(java.util.List.of(req1, req2));
        when(channelLookupService.getToolType(ak)).thenReturn(java.util.Optional.of("plugin-x"));
        when(channelSuppressReplyWhitelistService.shouldSuppress("plugin-x")).thenReturn(true);

        // Trigger via session_created flow
        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", toolSessionId);
        node.put("welinkSessionId", welinkSessionId);

        // Act
        r.route("session_created", ak, userId, node);

        // Assert: both pending messages replayed with suppressReply=TRUE
        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(2)).sendInvokeToGateway(captor.capture());
        for (com.opencode.cui.skill.model.InvokeCommand cmd : captor.getAllValues()) {
            assertEquals(Boolean.TRUE, cmd.suppressReply());
            assertEquals(GatewayActions.CHAT, cmd.action());
        }
    }

    @Test
    @DisplayName("retryPendingMessages: direct session -> never sets suppressReply (whitelist not consulted)")
    void retryPendingMessages_directSession_neverSetsSuppressReply() {
        // Arrange
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "1002";
        String toolSessionId = "tool-2";
        String ak = "ak-2";
        String userId = "user-2";

        SkillSession directSession = new SkillSession();
        directSession.setId(1002L);
        directSession.setBusinessSessionDomain("im");
        directSession.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(1002L)).thenReturn(directSession);

        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "ping", "assist-2", "owner-2", null, "msg-d", null, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId))
                .thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", toolSessionId);
        node.put("welinkSessionId", welinkSessionId);

        // Act
        r.route("session_created", ak, userId, node);

        // Assert: replay carries null suppressReply, channel lookup never invoked
        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        assertEquals(null, captor.getValue().suppressReply());
        verify(channelLookupService, never()).getToolType(anyString());
        verify(channelSuppressReplyWhitelistService, never()).shouldSuppress(anyString());
    }

    // ==================== PR2 retryPendingMessages 字段填充 ====================

    @Test
    @DisplayName("PR2: 完整 6 字段 PendingChatRequest -> chat invoke payload 6 字段全填入")
    void retryPendingMessages_fullPendingChatRequest_payloadContainsAllFields() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2001";
        String toolSessionId = "tool-2001";
        String ak = "ak-2001";
        String userId = "user-2001";

        SkillSession groupSession = new SkillSession();
        groupSession.setId(2001L);
        groupSession.setBusinessSessionDomain("im");
        groupSession.setBusinessSessionType("group");
        groupSession.setBusinessSessionId("biz-group-2001");
        when(sessionService.findByIdSafe(2001L)).thenReturn(groupSession);

        com.fasterxml.jackson.databind.JsonNode ext = objectMapper.readTree("{\"topicId\":777,\"source\":\"im\"}");
        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "你好", "assist-full", "sender-real-77", "biz-group-2001", "1717939200000", ext, "im", "group");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", toolSessionId);
        node.put("welinkSessionId", welinkSessionId);

        r.route("session_created", ak, userId, node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.opencode.cui.skill.model.InvokeCommand cmd = captor.getValue();
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(cmd.payload());

        assertEquals("你好", payload.path("text").asText());
        assertEquals(toolSessionId, payload.path("toolSessionId").asText());
        assertEquals("assist-full", payload.path("assistantAccount").asText());
        assertEquals("sender-real-77", payload.path("sendUserAccount").asText());
        assertEquals("biz-group-2001", payload.path("imGroupId").asText());
        assertEquals("1717939200000", payload.path("messageId").asText());
        // PR2: businessExtParam 已搬到 extParameters.businessExtParam（不再 payload 顶层）
        assertFalse(payload.has("businessExtParam"),
                "PR2: businessExtParam must NOT appear at payload top-level, moved into extParameters");
        com.fasterxml.jackson.databind.JsonNode extParameters = payload.path("extParameters");
        assertTrue(extParameters.isObject(), "PR2: extParameters envelope must be injected");
        assertEquals(777, extParameters.path("businessExtParam").path("topicId").asInt());
        assertEquals("im", extParameters.path("businessExtParam").path("source").asText());
        // platformExtParam 三字段：businessSessionDomain / businessSessionType / businessSessionId
        com.fasterxml.jackson.databind.JsonNode platform = extParameters.path("platformExtParam");
        assertEquals("im", platform.path("businessSessionDomain").asText());
        assertEquals("group", platform.path("businessSessionType").asText());
        assertEquals("biz-group-2001", platform.path("businessSessionId").asText());
    }

    @Test
    @DisplayName("PR2 platformExtParam: businessExtParam = Java null -> extParameters.businessExtParam = {}（顶层不出现）")
    void retryPendingMessages_businessExtParamJavaNull_omitFromPayload() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2002";
        SkillSession s = new SkillSession();
        s.setId(2002L);
        s.setBusinessSessionDomain("im");
        s.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2002L)).thenReturn(s);

        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "t", "assist-x", "user-x", null, "m-1", null, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-x");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-x", "user-x", node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().payload());

        // PR2 platformExtParam: 顶层 businessExtParam 永远不出现; extParameters.businessExtParam 兜底 {}
        assertFalse(payload.has("businessExtParam"),
                "PR2: businessExtParam must NOT appear at payload top-level");
        com.fasterxml.jackson.databind.JsonNode bep = payload.path("extParameters").path("businessExtParam");
        assertTrue(bep.isObject(), "extParameters.businessExtParam must default to empty {} when Java null");
        assertEquals(0, bep.size(), "default extParameters.businessExtParam should be empty {}");
    }

    @Test
    @DisplayName("PR2 platformExtParam: businessExtParam = NullNode -> extParameters.businessExtParam = {}（顶层不出现）")
    void retryPendingMessages_businessExtParamNullNode_omitFromPayload() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2003";
        SkillSession s = new SkillSession();
        s.setId(2003L);
        s.setBusinessSessionDomain("im");
        s.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2003L)).thenReturn(s);

        com.fasterxml.jackson.databind.node.NullNode nullNode = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "t", "assist-x", "user-x", null, "m-1", nullNode, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-x");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-x", "user-x", node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().payload());

        // PR2 platformExtParam: NullNode 与 Java null 同语义, 兜底 {}
        assertFalse(payload.has("businessExtParam"),
                "PR2: businessExtParam must NOT appear at payload top-level");
        com.fasterxml.jackson.databind.JsonNode bep = payload.path("extParameters").path("businessExtParam");
        assertTrue(bep.isObject(), "extParameters.businessExtParam must default to empty {} when NullNode");
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("PR2 platformExtParam: businessExtParam = ObjectNode -> 出现在 extParameters.businessExtParam 内")
    void retryPendingMessages_businessExtParamObject_includedInPayload() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2004";
        SkillSession s = new SkillSession();
        s.setId(2004L);
        s.setBusinessSessionDomain("im");
        s.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2004L)).thenReturn(s);

        com.fasterxml.jackson.databind.JsonNode ext = objectMapper.readTree("{\"k\":\"v\"}");
        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "t", "assist-x", "user-x", null, "m-1", ext, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-x");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-x", "user-x", node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().payload());

        // PR2: businessExtParam 已搬到 extParameters.businessExtParam
        assertFalse(payload.has("businessExtParam"),
                "PR2: businessExtParam must NOT appear at payload top-level");
        com.fasterxml.jackson.databind.JsonNode extParameters = payload.path("extParameters");
        assertTrue(extParameters.path("businessExtParam").isObject());
        assertEquals("v", extParameters.path("businessExtParam").path("k").asText());
    }

    @Test
    @DisplayName("PR2: 群聊 session -> payload.imGroupId == businessSessionId")
    void retryPendingMessages_groupSession_imGroupIdFromRequest() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2005";
        SkillSession group = new SkillSession();
        group.setId(2005L);
        group.setBusinessSessionDomain("im");
        group.setBusinessSessionType("group");
        group.setBusinessSessionId("biz-real-group-id-555");
        when(sessionService.findByIdSafe(2005L)).thenReturn(group);

        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "群消息", "assist-G", "sender-G", "biz-real-group-id-555", "m-G", null, "im", "group");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-G");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-G", "user-G", node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().payload());

        assertEquals("biz-real-group-id-555", payload.path("imGroupId").asText());
    }

    @Test
    @DisplayName("PR2: 单聊 session -> payload 含 'imGroupId':null（保留与 dispatchChatToGateway 一致的写 null 行为）")
    void retryPendingMessages_directSession_imGroupIdIsExplicitNull() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2006";
        SkillSession direct = new SkillSession();
        direct.setId(2006L);
        direct.setBusinessSessionDomain("im");
        direct.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2006L)).thenReturn(direct);

        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "单聊消息", "assist-D", "owner-D", null, "m-D", null, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-D");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-D", "user-D", node);

        org.mockito.ArgumentCaptor<com.opencode.cui.skill.model.InvokeCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.opencode.cui.skill.model.InvokeCommand.class);
        verify(sender, times(1)).sendInvokeToGateway(captor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(captor.getValue().payload());

        // 与 dispatchChatToGateway 行为一致：单聊 imGroupId 字段存在但 value 为 null
        assertTrue(payload.has("imGroupId"), "imGroupId field must be present even for direct session");
        assertTrue(payload.path("imGroupId").isNull(), "imGroupId value should be JSON null for direct session");
    }

    @Test
    @DisplayName("PR2: critical field missing (assistantAccount 缺失) -> 仍发送 + ERROR 日志")
    void retryPendingMessages_missingAssistantAccount_sendsAnyway() throws Exception {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2007";
        SkillSession s = new SkillSession();
        s.setId(2007L);
        s.setBusinessSessionDomain("im");
        s.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2007L)).thenReturn(s);

        // 半填 entry：text 有, assistantAccount 缺 → critical_field_missing
        com.opencode.cui.skill.model.PendingChatRequest req = new com.opencode.cui.skill.model.PendingChatRequest(
                "broken", null, "user-x", null, "m-broken", null, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId)).thenReturn(java.util.List.of(req));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-broken");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak-broken", "user-x", node);

        // 仍发送（保持当前行为不破坏 — 不静默 drop，让云端 fast-fail 暴露问题）
        verify(sender, times(1)).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("PR2: 空 PendingChatRequest list -> 不发送 invoke")
    void retryPendingMessages_emptyPending_noSend() {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        when(rebuildService.consumePendingRequests("2008")).thenReturn(java.util.List.of());

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-empty");
        node.put("welinkSessionId", "2008");
        r.route("session_created", "ak-empty", "user-empty", node);

        verify(sender, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("PR2: text 为 blank 的 entry 被跳过，其他 entry 继续发送")
    void retryPendingMessages_blankTextEntrySkipped() {
        GatewayMessageRouter r = buildRouter(true);
        GatewayMessageRouter.DownstreamSender sender =
                org.mockito.Mockito.mock(GatewayMessageRouter.DownstreamSender.class);
        r.setDownstreamSender(sender);

        String welinkSessionId = "2009";
        SkillSession s = new SkillSession();
        s.setId(2009L);
        s.setBusinessSessionDomain("im");
        s.setBusinessSessionType("direct");
        when(sessionService.findByIdSafe(2009L)).thenReturn(s);

        com.opencode.cui.skill.model.PendingChatRequest blankText = new com.opencode.cui.skill.model.PendingChatRequest(
                "", "assist", "user", null, "m-b", null, "im", "direct");
        com.opencode.cui.skill.model.PendingChatRequest goodOne = new com.opencode.cui.skill.model.PendingChatRequest(
                "real", "assist", "user", null, "m-r", null, "im", "direct");
        when(rebuildService.consumePendingRequests(welinkSessionId))
                .thenReturn(java.util.List.of(blankText, goodOne));

        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolSessionId", "tool-skip");
        node.put("welinkSessionId", welinkSessionId);
        r.route("session_created", "ak", "user", node);

        verify(sender, times(1)).sendInvokeToGateway(any());
    }

    /** 测试专用 Caffeine Ticker，可推进虚拟纳秒时间。 */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(0);

        void advance(long amount, TimeUnit unit) {
            nanos.addAndGet(unit.toNanos(amount));
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
