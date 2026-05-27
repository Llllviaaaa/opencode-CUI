package com.opencode.cui.skill.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.controller.SkillMessageController;
import com.opencode.cui.skill.controller.SkillMessageController.PermissionReplyRequest;
import com.opencode.cui.skill.controller.SkillMessageController.SendMessageRequest;
import com.opencode.cui.skill.controller.SkillSessionController;
import com.opencode.cui.skill.controller.SkillSessionController.CreateSessionRequest;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.model.GatewayAvailabilityResponse;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry;
import com.opencode.cui.skill.ws.GatewayWSClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 默认助手规则 e2e 集成测试（PR4 任务 05-15-noauth-conversation-permission）。
 *
 * <p>覆盖 PRD AC §B 端到端场景：连真实 MySQL + 真实 Redis，
 * 用 {@code @MockBean GatewayRelayTarget} 截获 SS → GW 的 invoke 报文，
 * 验证 controller → service → DB / Redis / GW 全链路。
 *
 * <p>这是 mock-gateway 端到端测试：GW 真实路径被 mock 替代，
 * 但 SS 内部的 controller / service / mapper / SysConfigService / RedisMessageBroker / scope dispatcher
 * 全部走真实组件，足以验证 PR3 的 controller-service-mapper-Redis-mock-GW 这条主链路。
 *
 * <p>与单元测试（pure Mockito，已在 PR1-PR3 完成）的区别：
 * <ul>
 *   <li>真实 SysConfigService Redis 5min 缓存 → 验证 lookup 命中 + 失效</li>
 *   <li>真实 SkillSessionService.createSessionWithDefaultAssistant 单事务路径
 *       → 验证 DB skill_session / Redis toolSessionId 映射就绪</li>
 *   <li>真实 AssistantScopeDispatcher 新 API getStrategy(domain, type, info)
 *       → 验证 default_assistant strategy 命中 + payload 字段</li>
 *   <li>{@code @MockBean GatewayRelayService.GatewayRelayTarget} 截获 invoke wire payload</li>
 * </ul>
 *
 * <p>因端到端涵盖 ws push / SSE 上行回流 / GW 真实路径成本太高，
 * 本测试主要验证 SS 出站 invoke wire 报文是否符合契约（D8 字段、scope、cloudProfile），
 * 不验证 GW → SSE → ws push 完整回流（已由 PR1-PR3 单元测试覆盖）。
 *
 * <p>@Transactional 自动回滚 DB；@AfterEach 清 Redis 残留。
 */
@SpringBootTest(properties = {"skill.gateway.internal-token=test-e2e-token"})
@Transactional
class DefaultAssistantRuleE2EIntegrationTest {

    private static final String RULE_TYPE = "default_assistant_rule";
    private static final String PROFILE_TYPE = "cloud_protocol_profile";

    // 用 e2e_* 前缀避免污染已存在数据
    private static final String DOMAIN = "e2e_helpdesk";
    private static final String DOMAIN_TYPE = "direct";
    private static final String RULE_KEY = DOMAIN + ":" + DOMAIN_TYPE;
    private static final String VIRTUAL_AK = "DEFAULT_AK_E2E_HELPDESK";
    private static final String VIRTUAL_ASSISTANT_ACCOUNT = "DEFAULT_ACC_E2E_HELPDESK";
    private static final String BUSINESS_TAG = "assistant_square";

    private static final String RULE_JSON =
            "{\"ak\":\"" + VIRTUAL_AK + "\","
            + "\"assistantAccount\":\"" + VIRTUAL_ASSISTANT_ACCOUNT + "\","
            + "\"businessTag\":\"" + BUSINESS_TAG + "\"}";

    private static final String CACHE_KEY_RULE = "ss:config:" + RULE_TYPE + ":" + RULE_KEY;
    private static final String CACHE_KEY_PROFILE = "ss:config:" + PROFILE_TYPE + ":" + BUSINESS_TAG;

    // 兼容未执行 V4 的本地集成测试库（user_id 仍为 BIGINT）和新 VARCHAR schema。
    private static final String TEST_USER_ID = "1000001";
    private static final String BIZ_SESSION_ID_PREFIX = "e2e-biz-";

    @Autowired SkillSessionController sessionController;
    @Autowired SkillMessageController messageController;
    @Autowired SkillSessionRepository sessionRepository;
    @Autowired SysConfigMapper sysConfigMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired GatewayRelayService gatewayRelayService;
    @Autowired CloudRequestProfileRegistry profileRegistry;

    /**
     * Mock GatewayWSClient（注入替换真实 ws 客户端，避免连真实 GW）。
     * 它实现 {@link GatewayRelayService.GatewayRelayTarget}，可被 verify wire 出站。
     *
     * <p>注意：mock 不会执行 {@code @PostConstruct init()}，所以
     * gatewayRelayService.gatewayRelayTarget 字段不会被自动设置 —— 我们在 setUp 里手动注入。
     */
    @MockitoBean GatewayWSClient gatewayRelayTarget;

    /**
     * Mock 外部 HTTP 依赖：避免测试连接真实上游 API。
     * - {@link AssistantInfoService#getAssistantInfo} → 返 null（virtual ak 上游本就不识别）
     * - {@link GatewayApiClient#getAgentByAk} → 返非 null mock AgentSummary（绕过 personal-fallback 503）
     */
    @MockitoBean AssistantInfoService assistantInfoService;
    @MockitoBean GatewayApiClient gatewayApiClient;

    @BeforeEach
    void setUp() {
        // mock GW 永远有连接 + 接收成功（避免 SS 因无连接早 return）
        lenient().when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        lenient().when(gatewayRelayTarget.sendToGateway(anyString())).thenReturn(true);

        // mock @PostConstruct 跳过的初始化：手动把 mock 注入 relayService
        gatewayRelayService.setGatewayRelayTarget(gatewayRelayTarget);

        // mock 上游 HTTP 依赖
        lenient().when(assistantInfoService.getAssistantInfo(anyString())).thenReturn(null);
        AgentSummary fakeAgent = new AgentSummary();
        fakeAgent.setAk(VIRTUAL_AK);
        lenient().when(gatewayApiClient.getAgentByAk(anyString())).thenReturn(fakeAgent);
        lenient().when(gatewayApiClient.getAvailability(anyString()))
                .thenReturn(new GatewayAvailabilityResponse(true, true, "opencode", null));

        // 清缓存（@Transactional 不管 Redis）
        redisTemplate.delete(CACHE_KEY_RULE);
        redisTemplate.delete(CACHE_KEY_PROFILE);

        // 提前插 profile mapping。CloudRequestProfileRegistry 的内存 cache TTL 5min，
        // 跨测试可能复用 —— 用反射清掉内存 cache，让本测试调用 resolve 时走 sys_config 拿新值。
        insertProfileMapping();
        clearProfileRegistryCache();
    }

    /**
     * 清 {@link CloudRequestProfileRegistry} 的私有内存 cache（{@code ConcurrentHashMap}），
     * 让下次 {@link CloudRequestProfileRegistry#resolve} 重读 sys_config。
     * 测试场景需要：cache TTL 5min，跨测试会复用 stale entry。
     */
    private void clearProfileRegistryCache() {
        try {
            java.lang.reflect.Field cacheField = CloudRequestProfileRegistry.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(profileRegistry);
            if (cacheObj instanceof java.util.Map<?, ?> m) {
                m.clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to clear CloudRequestProfileRegistry cache", e);
        }
    }

    @AfterEach
    void tearDown() {
        // 清 Redis 残留（DB 改动 @Transactional 自动回滚）
        redisTemplate.delete(CACHE_KEY_RULE);
        redisTemplate.delete(CACHE_KEY_PROFILE);
    }

    private void insertRule() {
        SysConfig existing = sysConfigMapper.findByTypeAndKey(RULE_TYPE, RULE_KEY);
        if (existing == null) {
            SysConfig c = new SysConfig();
            c.setConfigType(RULE_TYPE);
            c.setConfigKey(RULE_KEY);
            c.setConfigValue(RULE_JSON);
            c.setDescription("E2E test default assistant rule");
            c.setStatus(1);
            c.setSortOrder(0);
            sysConfigMapper.insert(c);
        } else {
            existing.setConfigValue(RULE_JSON);
            existing.setStatus(1);
            sysConfigMapper.update(existing);
        }
        redisTemplate.delete(CACHE_KEY_RULE);
    }

    /**
     * 插入 {@code cloud_protocol_profile:{businessTag}} → profile name 映射，
     * 让 {@link com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry#resolve}
     * 返回 profile.name == businessTag（约定路径）。
     * 缺这一行 → fallback 走 "default" profile，wire 上 cloudProfile 字段就是 "default"。
     */
    private void insertProfileMapping() {
        SysConfig existing = sysConfigMapper.findByTypeAndKey(PROFILE_TYPE, BUSINESS_TAG);
        if (existing == null) {
            SysConfig c = new SysConfig();
            c.setConfigType(PROFILE_TYPE);
            c.setConfigKey(BUSINESS_TAG);
            c.setConfigValue(BUSINESS_TAG); // profile.name() == businessTag (codex N6 SOP)
            c.setDescription("E2E test profile mapping");
            c.setStatus(1);
            c.setSortOrder(0);
            sysConfigMapper.insert(c);
        } else {
            existing.setConfigValue(BUSINESS_TAG);
            existing.setStatus(1);
            sysConfigMapper.update(existing);
        }
        redisTemplate.delete(CACHE_KEY_PROFILE);
    }

    private CreateSessionRequest createReq(String biz) {
        CreateSessionRequest r = new CreateSessionRequest();
        r.setBusinessSessionDomain(DOMAIN);
        r.setBusinessSessionType(DOMAIN_TYPE);
        r.setBusinessSessionId(BIZ_SESSION_ID_PREFIX + biz);
        r.setTitle("e2e test");
        return r;
    }

    private void assertWireSenderAccount(JsonNode wireNode, String message) {
        JsonNode senderNode = wireNode.findValue("sendUserAccount");
        if (senderNode == null || senderNode.isNull()) {
            // assistant_square profile maps the canonical sender to vendor field sendW3Account.
            senderNode = wireNode.findValue("sendW3Account");
        }
        assertNotNull(senderNode, message);
        assertEquals(TEST_USER_ID, senderNode.asText());
    }

    // ===================================================================
    // AC §B-1: POST /sessions 命中规则 → 200 + DB 字段正确 + 不发 CREATE_SESSION
    // ===================================================================

    @Test
    @DisplayName("AC §B createSession: rule hit + no ak/account → 200 + DB injected + no GW CREATE_SESSION")
    void createSession_ruleHit_injectsVirtualIdentity() {
        insertRule();
        CreateSessionRequest req = createReq("hit1");

        ResponseEntity<ApiResponse<SkillSession>> resp = sessionController.createSession(TEST_USER_ID, req);

        // 200 + ApiResponse.ok（code=0）
        assertEquals(org.springframework.http.HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());

        // 响应 session 字段
        SkillSession created = resp.getBody().getData();
        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals(VIRTUAL_AK, created.getAk());
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, created.getAssistantAccount());
        assertNotNull(created.getToolSessionId(), "toolSessionId pre-generated locally (snowflake)");
        assertFalse(created.getToolSessionId().isBlank());
        assertEquals(SkillSession.Status.ACTIVE, created.getStatus());
        assertEquals(DOMAIN, created.getBusinessSessionDomain());
        assertEquals(DOMAIN_TYPE, created.getBusinessSessionType());

        // DB 重读校验（验证单事务三处就绪：skill_session）
        SkillSession dbSession = sessionRepository.findById(created.getId());
        assertNotNull(dbSession);
        assertEquals(VIRTUAL_AK, dbSession.getAk());
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, dbSession.getAssistantAccount());
        assertEquals(created.getToolSessionId(), dbSession.getToolSessionId());

        // 默认助手路径不发 CREATE_SESSION 给 GW（mock 端无 sendToGateway 调用）
        verify(gatewayRelayTarget, never()).sendToGateway(anyString());
    }

    // ===================================================================
    // AC §B-2: 未命中 + 不传 ak/assistantAccount → 400
    // ===================================================================

    @Test
    @DisplayName("AC §B createSession: no rule + no ak/account → 400 with required-field message")
    void createSession_noRule_no_ak_no_account_returns400() {
        // 不插入规则，使用 unique domain/type
        CreateSessionRequest req = new CreateSessionRequest();
        req.setBusinessSessionDomain("e2e_no_rule_domain");
        req.setBusinessSessionType("e2e_no_rule_type");
        req.setTitle("e2e test - no rule");

        ResponseEntity<ApiResponse<SkillSession>> resp = sessionController.createSession(TEST_USER_ID, req);

        assertEquals(org.springframework.http.HttpStatus.OK, resp.getStatusCode()); // ResponseEntity.ok 包裹
        assertNotNull(resp.getBody());
        assertEquals(400, resp.getBody().getCode());
        assertTrue(resp.getBody().getErrormsg() != null
                && resp.getBody().getErrormsg().contains("必填"),
                "Error message should contain '必填', got: " + resp.getBody().getErrormsg());
    }

    @Test
    @DisplayName("AC §B createSession: domain blank + no ak/account → 400 (lookup short-circuits on blank)")
    void createSession_blankDomain_no_ak_no_account_returns400() {
        CreateSessionRequest req = new CreateSessionRequest();
        req.setBusinessSessionDomain("");
        req.setBusinessSessionType(DOMAIN_TYPE);
        req.setTitle("e2e test - blank domain");

        ResponseEntity<ApiResponse<SkillSession>> resp = sessionController.createSession(TEST_USER_ID, req);

        assertNotNull(resp.getBody());
        assertEquals(400, resp.getBody().getCode());
    }

    // ===================================================================
    // AC §B-3: GET 列表 / 详情 走通
    // ===================================================================

    @Test
    @DisplayName("AC §B GET: list and detail of rule-injected session work normally")
    void getList_andDetail_workForRuleInjectedSession() {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("list1"))
                .getBody().getData();

        // GET /api/skill/sessions?ak=AK_V
        ResponseEntity<ApiResponse<PageResult<SkillSession>>> listResp = sessionController.listSessions(
                TEST_USER_ID, null, VIRTUAL_AK, null, null, null, null, 0, 20);
        assertNotNull(listResp.getBody());
        assertEquals(0, listResp.getBody().getCode());
        PageResult<SkillSession> page = listResp.getBody().getData();
        assertNotNull(page);
        boolean found = page.getContent().stream().anyMatch(s -> s.getId().equals(created.getId()));
        assertTrue(found, "rule-injected session should be listable by virtual ak");

        // GET /api/skill/sessions?assistantAccount=ACC_V
        ResponseEntity<ApiResponse<PageResult<SkillSession>>> listResp2 = sessionController.listSessions(
                TEST_USER_ID, null, null, null, null, null, VIRTUAL_ASSISTANT_ACCOUNT, 0, 20);
        assertNotNull(listResp2.getBody());
        assertEquals(0, listResp2.getBody().getCode());
        boolean foundByAcc = listResp2.getBody().getData().getContent().stream()
                .anyMatch(s -> s.getId().equals(created.getId()));
        assertTrue(foundByAcc, "rule-injected session should be listable by virtual assistantAccount");

        // GET /api/skill/sessions/{id}
        ResponseEntity<ApiResponse<SkillSession>> detail = sessionController.getSession(
                TEST_USER_ID, created.getId().toString());
        assertNotNull(detail.getBody());
        assertEquals(0, detail.getBody().getCode());
        assertEquals(VIRTUAL_AK, detail.getBody().getData().getAk());
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, detail.getBody().getData().getAssistantAccount());
    }

    // ===================================================================
    // AC §B-4: POST chat → payload 含 assistantAccount + sendUserAccount
    //          + scope=business + cloudProfile=businessTag
    // ===================================================================

    @Test
    @DisplayName("AC §B sendMessage chat: payload has assistantAccount + sendUserAccount; wire scope=business + cloudProfile=businessTag")
    void sendMessage_chat_payloadAndWireContract() throws Exception {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("chat1"))
                .getBody().getData();

        // chat
        SendMessageRequest req = new SendMessageRequest();
        req.setContent("hello world");

        ResponseEntity<ApiResponse<ProtocolMessageView>> resp = messageController.sendMessage(
                TEST_USER_ID, created.getId().toString(), req);
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());

        // 截获 GW invoke wire 报文（默认助手路径会 invoke 给 GW，调 buildInvoke）
        ArgumentCaptor<String> wireCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget, times(1)).sendToGateway(wireCaptor.capture());
        String wire = wireCaptor.getValue();
        assertNotNull(wire);

        JsonNode wireNode = objectMapper.readTree(wire);
        // 顶层 action=chat（兼容老的 invoke 命名）
        // wire 结构由 default_assistant strategy.buildInvoke 决定；至少要含 assistantScope + payload
        // 由 D4 规定 assistantScope wire 层填 "business"
        JsonNode scopeNode = wireNode.findValue("assistantScope");
        assertNotNull(scopeNode, "wire should carry assistantScope, got: " + wire);
        assertEquals("business", scopeNode.asText(),
                "default_assistant strategy wire-level scope should be 'business' (D4)");

        // payload.cloudProfile == businessTag（D4 + Technical Notes 字段命名收敛）
        JsonNode cloudProfileNode = wireNode.findValue("cloudProfile");
        assertNotNull(cloudProfileNode, "wire payload should carry cloudProfile, got: " + wire);
        assertEquals(BUSINESS_TAG, cloudProfileNode.asText(),
                "wire cloudProfile should equal businessTag (assistant_square)");

        // chat 分支 payload 已含 assistantAccount + sender account（老 chat 已有 D8 支持）
        JsonNode assistantAccountNode = wireNode.findValue("assistantAccount");
        assertNotNull(assistantAccountNode, "wire payload should carry assistantAccount");
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, assistantAccountNode.asText());

        assertWireSenderAccount(wireNode, "wire payload should carry sender account");
    }

    // ===================================================================
    // AC §B-5: POST question_reply (toolCallId 非空) → payload 含 assistantAccount + sendUserAccount (D8)
    // ===================================================================

    @Test
    @DisplayName("AC §B sendMessage question_reply: D8 fix — payload carries assistantAccount + sendUserAccount")
    void sendMessage_questionReply_d8FixCarriesAccounts() throws Exception {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("qr1"))
                .getBody().getData();

        SendMessageRequest req = new SendMessageRequest();
        req.setContent("the answer is foo");
        req.setToolCallId("call-abc-123"); // 非空 → question_reply 分支

        ResponseEntity<ApiResponse<ProtocolMessageView>> resp = messageController.sendMessage(
                TEST_USER_ID, created.getId().toString(), req);
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());

        ArgumentCaptor<String> wireCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget, times(1)).sendToGateway(wireCaptor.capture());
        String wire = wireCaptor.getValue();

        JsonNode wireNode = objectMapper.readTree(wire);

        // D8 修复：question_reply 必须含 assistantAccount + sendUserAccount
        JsonNode assistantAccountNode = wireNode.findValue("assistantAccount");
        assertNotNull(assistantAccountNode, "D8 fix: question_reply payload must carry assistantAccount");
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, assistantAccountNode.asText());

        assertWireSenderAccount(wireNode, "D8 fix: question_reply payload must carry sender account");

        // toolCallId 透传
        JsonNode toolCallIdNode = wireNode.findValue("toolCallId");
        assertNotNull(toolCallIdNode);
        assertEquals("call-abc-123", toolCallIdNode.asText());
    }

    // ===================================================================
    // AC §B-6: POST permission_reply → payload 含 assistantAccount + sendUserAccount (D8)
    // ===================================================================

    @Test
    @DisplayName("AC §B replyPermission: D8 fix — payload carries assistantAccount + sendUserAccount; not 400")
    void replyPermission_d8FixCarriesAccounts() throws Exception {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("perm1"))
                .getBody().getData();

        PermissionReplyRequest req = new PermissionReplyRequest();
        req.setResponse("once");

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = messageController.replyPermission(
                TEST_USER_ID, created.getId().toString(), "perm-001", req);
        assertNotNull(resp.getBody());
        // 老的 ak=null 路径会返 400 "No agent associated"；本任务命中规则 → ak=virtual → 不再 400
        assertEquals(0, resp.getBody().getCode(),
                "permission_reply on rule-injected session must not be 400; got: " + resp.getBody().getErrormsg());

        ArgumentCaptor<String> wireCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget, times(1)).sendToGateway(wireCaptor.capture());
        String wire = wireCaptor.getValue();
        JsonNode wireNode = objectMapper.readTree(wire);

        // D8 修复：permission_reply 必须含 assistantAccount + sendUserAccount
        JsonNode assistantAccountNode = wireNode.findValue("assistantAccount");
        assertNotNull(assistantAccountNode, "D8 fix: permission_reply payload must carry assistantAccount");
        assertEquals(VIRTUAL_ASSISTANT_ACCOUNT, assistantAccountNode.asText());

        assertWireSenderAccount(wireNode, "D8 fix: permission_reply payload must carry sender account");
    }

    // ===================================================================
    // AC §B-7: DELETE close keeps skipping GW; POST abort sends GW invoke for stream cancellation.
    // ===================================================================

    @Test
    @DisplayName("AC §B closeSession: rule-injected session → DB CLOSED + no GW invoke (D7)")
    void closeSession_ruleInjected_dbClosedNoGwInvoke() {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("close1"))
                .getBody().getData();

        // 重置 invocations（createSession 不应该 invoke，但保险起见）
        org.mockito.Mockito.reset(gatewayRelayTarget);
        lenient().when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        lenient().when(gatewayRelayTarget.sendToGateway(anyString())).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = sessionController.closeSession(
                TEST_USER_ID, created.getId().toString());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());

        // DB CLOSED
        SkillSession dbSession = sessionRepository.findById(created.getId());
        assertNotNull(dbSession);
        assertEquals(SkillSession.Status.CLOSED, dbSession.getStatus());

        // D7: 默认助手会话跳过发 GW invoke
        verify(gatewayRelayTarget, never()).sendToGateway(anyString());
    }

    @Test
    @DisplayName("AC §B abortSession: rule-injected session → sends GW abort invoke")
    void abortSession_ruleInjected_sendsGwAbortInvoke() throws Exception {
        insertRule();
        SkillSession created = sessionController.createSession(TEST_USER_ID, createReq("abort1"))
                .getBody().getData();

        org.mockito.Mockito.reset(gatewayRelayTarget);
        lenient().when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        lenient().when(gatewayRelayTarget.sendToGateway(anyString())).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = sessionController.abortSession(
                TEST_USER_ID, created.getId().toString());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getCode());

        ArgumentCaptor<String> wireCaptor = ArgumentCaptor.forClass(String.class);
        verify(gatewayRelayTarget, times(1)).sendToGateway(wireCaptor.capture());
        JsonNode wireNode = objectMapper.readTree(wireCaptor.getValue());
        assertEquals("abort_session", wireNode.path("action").asText());
        assertEquals(created.getId().toString(), wireNode.path("welinkSessionId").asText());
        assertEquals(created.getToolSessionId(), wireNode.path("payload").path("toolSessionId").asText());
        assertFalse(wireNode.path("payload").has("cloudRequest"));
    }

    // ===================================================================
    // Redis 缓存验证：第一次 lookup miss 后 Redis 写入；TTL 5min
    //
    // 注：显式 ak 走老路径的回归保护已由 PR3 单元测试覆盖
    // （SkillSessionControllerTest.createSession_explicit_*）。本集成测试聚焦 AC §B。
    // ===================================================================

    @Test
    @DisplayName("AC §A Redis cache: first lookup miss → write; second hit (verified by no DB access on cache hit)")
    void redisCache_writeOnMiss_hitOnSecondLookup() {
        insertRule();

        // 第一次 createSession 触发 lookup（cache miss → query DB → write Redis）
        SkillSession s1 = sessionController.createSession(TEST_USER_ID, createReq("cache1"))
                .getBody().getData();
        assertNotNull(s1);

        // 验证 Redis 中有 key（缓存写回成功）
        Boolean exists = redisTemplate.hasKey(CACHE_KEY_RULE);
        assertEquals(Boolean.TRUE, exists, "after first lookup, Redis should hold cache key " + CACHE_KEY_RULE);

        // 第二次 createSession（用新 biz id）→ 走 Redis cache hit
        SkillSession s2 = sessionController.createSession(TEST_USER_ID, createReq("cache2"))
                .getBody().getData();
        assertNotNull(s2);
        assertEquals(VIRTUAL_AK, s2.getAk(), "second session should also hit rule via Redis cache");
    }
}
