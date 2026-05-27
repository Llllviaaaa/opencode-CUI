package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** SessionRebuildService 单元测试：验证 PR2 新签名 + 老格式 fallback + 重建计数器超限行为。 */
class SessionRebuildServiceTest {

    @Mock
    private SkillSessionService sessionService;

    @Mock
    private SkillMessageRepository messageRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private AllowedSlashCommandsResolver allowedSlashCommandsResolver;

    @Mock
    private AssistantInfoService assistantInfoService;

    @Mock
    private AssistantScopeDispatcher scopeDispatcher;

    @Mock
    private AssistantScopeStrategy personalStrategy;

    private ObjectMapper objectMapper;
    private SessionRebuildService service;

    /** 捕获回调，将广播和 invoke 命令分别存入列表便于断言。 */
    static class CapturingCallback implements SessionRebuildService.RebuildCallback {
        final List<StreamMessage> broadcasts = new ArrayList<>();
        final List<InvokeCommand> invokes = new ArrayList<>();

        @Override
        public void broadcast(String sessionId, String userId, StreamMessage msg) {
            broadcasts.add(msg);
        }

        @Override
        public void sendInvoke(InvokeCommand command) {
            invokes.add(command);
        }
    }

    /** 构建一个带 ak 的测试用 SkillSession。 */
    private SkillSession buildSession(Long id, String userId, String ak, String title) {
        return SkillSession.builder()
                .id(id)
                .userId(userId)
                .ak(ak)
                .title(title)
                .build();
    }

    /**
     * 构建完整 IM 群聊 session, 用于显式群聊语义测试。
     */
    private SkillSession buildImGroupSession(Long id, String owner, String assistantAccount, String businessSessionId) {
        SkillSession s = new SkillSession();
        s.setId(id);
        s.setUserId(owner);
        s.setAssistantAccount(assistantAccount);
        s.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        s.setBusinessSessionType(SkillSession.SESSION_TYPE_GROUP);
        s.setBusinessSessionId(businessSessionId);
        return s;
    }

    /**
     * 构建完整 IM 单聊 session, 用于 fromSessionFallback 成功路径。
     */
    private SkillSession buildImDirectSession(Long id, String owner, String assistantAccount, String businessSessionId) {
        SkillSession s = new SkillSession();
        s.setId(id);
        s.setUserId(owner);
        s.setAssistantAccount(assistantAccount);
        s.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        s.setBusinessSessionType(SkillSession.SESSION_TYPE_DIRECT);
        s.setBusinessSessionId(businessSessionId);
        return s;
    }

    /**
     * 为指定 sessionId 配置 Redis 计数器 mock，模拟原子递增行为。
     * 返回 AtomicLong 方便测试中检查计数器状态。
     */
    private AtomicLong stubRedisCounter(String sessionId) {
        AtomicLong counter = new AtomicLong(0);
        lenient().when(valueOperations.increment("ss:rebuild-counter:" + sessionId))
                .thenAnswer(inv -> counter.incrementAndGet());
        return counter;
    }

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        // expire / delete 默认返回 true
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        lenient().when(redisTemplate.delete(anyString())).thenReturn(true);

        objectMapper = new ObjectMapper();
        // resolver / scope mocks 默认无 stub —— lenient 配合即可
        lenient().when(allowedSlashCommandsResolver.resolve(anyString(), anyString())).thenReturn(null);
        // personal scope strategy: generateToolSessionId() == null
        lenient().when(personalStrategy.generateToolSessionId()).thenReturn(null);
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalStrategy);

        // maxRebuildAttempts=3, rebuildCooldownSeconds=30
        service = new SessionRebuildService(
                objectMapper,
                sessionService,
                messageRepository,
                redisTemplate,
                allowedSlashCommandsResolver,
                assistantInfoService,
                scopeDispatcher,
                3,
                30);
    }

    // ==================== 既有回归测试：rebuildToolSession 计数器 ====================

    @Test
    @DisplayName("在限额内的重建次数：3 次都应发送 invoke 并广播 retry 状态")
    void rebuildToolSession_shouldAllowAttemptsWithinLimit() {
        String sessionId = "1001";
        stubRedisCounter(sessionId);
        SkillSession session = buildSession(1001L, "user-1", "ak-abc", "测试会话");
        CapturingCallback cb = new CapturingCallback();

        service.rebuildToolSession(sessionId, session, "用户消息", cb);
        service.rebuildToolSession(sessionId, session, "用户消息", cb);
        service.rebuildToolSession(sessionId, session, "用户消息", cb);

        // 3 次重建均应触发 invoke
        assertEquals(3, cb.invokes.size(), "3 次重建都应发送 invoke");
        // 每次重建都应广播 retry 状态（session.status = retry）
        long retryCount = cb.broadcasts.stream()
                .filter(m -> StreamMessage.Types.SESSION_STATUS.equals(m.getType()))
                .filter(m -> "retry".equals(m.getSessionStatus()))
                .count();
        assertEquals(3, retryCount, "每次重建都应广播 retry 状态");
    }

    @Test
    @DisplayName("超出限额：第 4 次应阻断 invoke 并广播含「重建已达上限」的错误消息，同时清除 toolSessionId")
    void rebuildToolSession_shouldBlockAfterMaxAttempts() {
        String sessionId = "1002";
        stubRedisCounter(sessionId);
        SkillSession session = buildSession(1002L, "user-2", "ak-def", "溢出会话");
        CapturingCallback cb = new CapturingCallback();

        // 前 3 次在限额内，应正常通过
        service.rebuildToolSession(sessionId, session, "消息", cb);
        service.rebuildToolSession(sessionId, session, "消息", cb);
        service.rebuildToolSession(sessionId, session, "消息", cb);

        // 第 4 次超限
        service.rebuildToolSession(sessionId, session, "消息", cb);

        // invoke 只应被调用 3 次，第 4 次不应触发
        assertEquals(3, cb.invokes.size(), "超限后不应再发送 invoke");

        // 第 4 次广播应包含「重建已达上限」错误消息
        StreamMessage lastBroadcast = cb.broadcasts.get(cb.broadcasts.size() - 1);
        assertEquals(StreamMessage.Types.ERROR, lastBroadcast.getType(), "超限广播应为 error 类型");
        assertNotNull(lastBroadcast.getError(), "error 字段不应为 null");
        assertTrue(lastBroadcast.getError().contains("重建已达上限"),
                "错误消息应包含「重建已达上限」，实际：" + lastBroadcast.getError());

        // 应调用 clearToolSessionId
        verify(sessionService, times(1)).clearToolSessionId(1002L);
    }

    @Test
    @DisplayName("不同会话拥有独立计数器：会话 A 耗尽后，会话 B 仍可正常重建")
    void rebuildToolSession_differentSessionsShouldHaveSeparateCounters() {
        stubRedisCounter("2001");
        stubRedisCounter("2002");
        SkillSession sessionA = buildSession(2001L, "user-a", "ak-aaa", "会话A");
        SkillSession sessionB = buildSession(2002L, "user-b", "ak-bbb", "会话B");
        CapturingCallback cbA = new CapturingCallback();
        CapturingCallback cbB = new CapturingCallback();

        // 耗尽会话 A 的计数器（3 次）
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        service.rebuildToolSession("2001", sessionA, "msg", cbA);

        // 验证第 4 次对会话 A 已被阻断
        service.rebuildToolSession("2001", sessionA, "msg", cbA);
        assertEquals(3, cbA.invokes.size(), "会话 A 第 4 次应被阻断");

        // 会话 B 的第 1 次重建应正常通过
        service.rebuildToolSession("2002", sessionB, "msg", cbB);
        assertEquals(1, cbB.invokes.size(), "会话 B 应有独立计数器，第 1 次应成功");
        // 会话 B 未超限，不应调用 clearToolSessionId
        verify(sessionService, never()).clearToolSessionId(2002L);
    }

    @Test
    @DisplayName("超限后 consumePendingRequests 应返回空列表（待重建消息已被清除）")
    void rebuildToolSession_blockedAttemptShouldClearPendingMessages() {
        String sessionId = "3001";
        stubRedisCounter(sessionId);
        // consumePendingRequests 调用 range 后 delete，超限后 clearPendingMessages 已 delete 过
        // range 返回空列表模拟消息已清除
        when(listOperations.range("ss:pending-rebuild:" + sessionId, 0, -1))
                .thenReturn(Collections.emptyList());
        SkillSession session = buildSession(3001L, "user-3", "ak-ghi", "消息清理会话");
        CapturingCallback cb = new CapturingCallback();

        // 耗尽计数器
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);

        // 第 4 次触发超限清除逻辑
        service.rebuildToolSession(sessionId, session, "待重试消息", cb);

        // 超限后，待重建消息缓存应已清空
        List<PendingChatRequest> pending = service.consumePendingRequests(sessionId);
        assertTrue(pending.isEmpty(), "超限后 consumePendingRequests 应返回空列表");

        // 验证 clearPendingMessages 被调用（delete 对应 key）
        verify(redisTemplate, atLeastOnce()).delete("ss:pending-rebuild:" + sessionId);
    }

    // ==================== PR2 新签名：append + consume 往返 ====================

    @Test
    @DisplayName("PR2 新签名往返：append PendingChatRequest → consume 拿回完整 6 字段")
    void append_then_consume_newSignature_preservesAllFields() throws Exception {
        String sessionId = "5001";
        String key = "ss:pending-rebuild:" + sessionId;

        JsonNode ext = objectMapper.readTree("{\"topicId\":42}");
        PendingChatRequest original = new PendingChatRequest(
                "hello", "assist-01", "user-real", "group-001", "msg-1", ext, "im", "group");

        // 用 ArgumentCaptor 捕获 rightPush 的 value，然后让 range 返回它（模拟 list FIFO）
        List<String> captured = new ArrayList<>();
        when(listOperations.rightPush(eq(key), anyString())).thenAnswer(inv -> {
            captured.add(inv.getArgument(1));
            return 1L;
        });
        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenAnswer(inv -> new ArrayList<>(captured));

        service.appendPendingMessage(sessionId, original);
        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size(), "应消费一条 entry");
        PendingChatRequest got = consumed.get(0);
        assertEquals("hello", got.text());
        assertEquals("assist-01", got.assistantAccount());
        assertEquals("user-real", got.sendUserAccount());
        assertEquals("group-001", got.imGroupId());
        assertEquals("msg-1", got.messageId());
        assertNotNull(got.businessExtParam());
        assertEquals(42, got.businessExtParam().get("topicId").asInt());
    }

    @Test
    @DisplayName("PR2 新签名 peek：不消费 list（不调 delete），返回结构化列表")
    void peek_newSignature_doesNotDelete() throws Exception {
        String sessionId = "5002";
        String key = "ss:pending-rebuild:" + sessionId;

        PendingChatRequest req = new PendingChatRequest(
                "peek-text", "assist-02", "owner-2", null, "msg-x", null, "im", "direct");
        String json = objectMapper.writeValueAsString(req);

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(json));

        List<PendingChatRequest> peeked = service.peekPendingRequests(sessionId);

        assertEquals(1, peeked.size());
        assertEquals("peek-text", peeked.get(0).text());
        // peek 不应触发 delete
        verify(redisTemplate, never()).delete(eq(key));
    }

    // ==================== 老格式 fallback 路径 ====================

    @Test
    @DisplayName("M1 对抗输入：rawValue 是合法 JSON 但缺 text 字段（如 {\"foo\":\"bar\"}）→ 视为 plain-text fallback")
    void consume_jsonWithoutTextField_fallsBackToPlainText() {
        String sessionId = "5101";
        String key = "ss:pending-rebuild:" + sessionId;
        String adversarial = "{\"foo\":\"bar\"}";

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(adversarial));
        when(sessionService.findByIdSafe(5101L)).thenReturn(
                buildImDirectSession(5101L, "owner-x", "assist-x", "biz-group-1"));

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size());
        // raw 整体当 text（不能丢失原始内容）
        assertEquals(adversarial, consumed.get(0).text());
        // 其他字段由 fromSessionFallback 反查
        assertEquals("assist-x", consumed.get(0).assistantAccount());
        assertEquals("owner-x", consumed.get(0).sendUserAccount());
        assertNull(consumed.get(0).imGroupId());
        assertNotNull(consumed.get(0).messageId());
        assertNull(consumed.get(0).businessExtParam());
    }

    @Test
    @DisplayName("老格式 — readTree 失败（如 'not-a-json{'）→ 整个 raw 当 text，走 session fallback")
    void consume_invalidJsonString_fallsBackToPlainText() {
        String sessionId = "5102";
        String key = "ss:pending-rebuild:" + sessionId;
        String legacyPlain = "not-a-json{";

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(legacyPlain));
        when(sessionService.findByIdSafe(5102L)).thenReturn(
                buildImDirectSession(5102L, "owner-y", "assist-y", "biz-group-2"));

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size());
        assertEquals(legacyPlain, consumed.get(0).text());
        assertEquals("assist-y", consumed.get(0).assistantAccount());
        assertNull(consumed.get(0).imGroupId());
    }

    @Test
    @DisplayName("readTree 成功但不是 object（如合法 JSON 字符串 \"just a string\"）→ 视为 plain-text fallback")
    void consume_jsonStringPrimitive_fallsBackToPlainText() {
        String sessionId = "5103";
        String key = "ss:pending-rebuild:" + sessionId;
        String legalJsonString = "\"just a string\"";

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(legalJsonString));
        when(sessionService.findByIdSafe(5103L)).thenReturn(
                buildImDirectSession(5103L, "owner-z", "assist-z", "biz-group-3"));

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size());
        // raw 字符串保留（含外层引号）—— 不要解 JSON, 否则会丢失"用户输入的原文恰好是合法 JSON 字符串"的语义
        assertEquals(legalJsonString, consumed.get(0).text());
        assertEquals("assist-z", consumed.get(0).assistantAccount());
    }

    @Test
    @DisplayName("fromSessionFallback IAE 兜底：session 缺 userId/assistantAccount → ERROR 日志 + 半填 entry")
    void consume_sessionMissingAccountFields_returnsHalfFilledEntry() {
        String sessionId = "5104";
        String key = "ss:pending-rebuild:" + sessionId;
        String legacyPlain = "raw text";

        // 缺 userId → fromSessionFallback 会抛 IAE
        SkillSession brokenSession = new SkillSession();
        brokenSession.setId(5104L);
        brokenSession.setUserId(null);
        brokenSession.setAssistantAccount("assist-broken");
        brokenSession.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        brokenSession.setBusinessSessionType(SkillSession.SESSION_TYPE_DIRECT);

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(legacyPlain));
        when(sessionService.findByIdSafe(5104L)).thenReturn(brokenSession);

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size(), "IAE 不应静默吞掉消息，仍要进结果集");
        // 半填 entry: 只有 text，其他字段全 null
        assertEquals(legacyPlain, consumed.get(0).text());
        assertNull(consumed.get(0).assistantAccount(), "IAE 兜底后 assistantAccount 必须为 null（让下游 critical_field_missing 日志暴露）");
        assertNull(consumed.get(0).sendUserAccount());
        assertNull(consumed.get(0).imGroupId());
        assertNull(consumed.get(0).businessExtParam());
    }

    @Test
    @DisplayName("session 不存在（findByIdSafe 返 null）→ WARN 日志 + 半填 entry")
    void consume_sessionNotFound_returnsHalfFilledEntry() {
        String sessionId = "5105";
        String key = "ss:pending-rebuild:" + sessionId;
        String legacyPlain = "ghost-session text";

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(List.of(legacyPlain));
        when(sessionService.findByIdSafe(5105L)).thenReturn(null);

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(1, consumed.size(), "session 不存在不应静默吞消息");
        assertEquals(legacyPlain, consumed.get(0).text());
        assertNull(consumed.get(0).assistantAccount());
        assertNull(consumed.get(0).sendUserAccount());
    }

    @Test
    @DisplayName("新 + 老格式混在同一 list：consume 按 FIFO 顺序返还两种格式都正确还原")
    void consume_mixedFormats_preservesOrderAndFormat() throws Exception {
        String sessionId = "5106";
        String key = "ss:pending-rebuild:" + sessionId;

        PendingChatRequest newFormat = new PendingChatRequest(
                "new-msg", "assist-A", "user-A", "group-A", "id-A", null, "im", "group");
        String newFormatJson = objectMapper.writeValueAsString(newFormat);
        String legacyPlain = "legacy-msg";

        // 顺序：新 → 老（验证 FIFO 保留）
        when(listOperations.range(eq(key), eq(0L), eq(-1L)))
                .thenReturn(Arrays.asList(newFormatJson, legacyPlain));
        when(sessionService.findByIdSafe(5106L)).thenReturn(
                buildImDirectSession(5106L, "owner-mix", "assist-mix", "biz-mix"));

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertEquals(2, consumed.size());
        // 第 1 条：新格式
        assertEquals("new-msg", consumed.get(0).text());
        assertEquals("assist-A", consumed.get(0).assistantAccount());
        assertEquals("user-A", consumed.get(0).sendUserAccount());
        assertEquals("group-A", consumed.get(0).imGroupId());
        // 第 2 条：老格式 fallback
        assertEquals("legacy-msg", consumed.get(1).text());
        assertEquals("assist-mix", consumed.get(1).assistantAccount());
        assertEquals("owner-mix", consumed.get(1).sendUserAccount());
        assertNull(consumed.get(1).imGroupId());
    }

    // ==================== @Deprecated 老签名：仅保留 peekPendingMessages（PR3 已删 append/consume String 重载） ====================

    @Test
    @DisplayName("@Deprecated 旧 peekPendingMessages：内部走新签名，返回 text 列表，不消费 list")
    void deprecated_peekStringList_returnsTextList_noDelete() throws Exception {
        String sessionId = "5203";
        String key = "ss:pending-rebuild:" + sessionId;

        PendingChatRequest req = new PendingChatRequest(
                "deprecated-peek", "assist-P", "user-P", null, "id-P", null, "im", "direct");
        when(listOperations.range(eq(key), eq(0L), eq(-1L)))
                .thenReturn(List.of(objectMapper.writeValueAsString(req)));

        List<String> texts = service.peekPendingMessages(sessionId);

        assertEquals(1, texts.size());
        assertEquals("deprecated-peek", texts.get(0));
        verify(redisTemplate, never()).delete(eq(key));
    }

    // ==================== Redis 故障兜底（回归） ====================

    @Test
    @DisplayName("Redis range 抛异常：consume 返回空列表，不抛")
    void consume_redisFailure_returnsEmpty() {
        String sessionId = "5301";
        String key = "ss:pending-rebuild:" + sessionId;
        when(listOperations.range(eq(key), eq(0L), eq(-1L)))
                .thenThrow(new RuntimeException("Redis down"));

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertTrue(consumed.isEmpty());
    }

    @Test
    @DisplayName("空 list（range 返 null）：consume 返回空列表")
    void consume_emptyList_returnsEmpty() {
        String sessionId = "5302";
        String key = "ss:pending-rebuild:" + sessionId;
        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(null);

        List<PendingChatRequest> consumed = service.consumePendingRequests(sessionId);

        assertTrue(consumed.isEmpty());
    }

    // ==================== PR3: rebuildFromStoredUserMessage 完整路径 ====================

    @Test
    @DisplayName("PR3: rebuildFromStoredUserMessage 完整 DB rebuild + fromSessionFallback 成功 → appendPendingMessage 入队 PendingChatRequest")
    void rebuildFromStoredUserMessage_dbHit_appendsPendingChatRequest() {
        String sessionId = "6001";
        String key = "ss:pending-rebuild:" + sessionId;
        Long sessionIdLong = 6001L;
        stubRedisCounter(sessionId);

        // 拿到 rebuild lock 成功
        when(valueOperations.setIfAbsent(eq("ss:rebuild-lock:" + sessionId), anyString(), any()))
                .thenReturn(true);

        // session 反查：单聊场景，字段齐全
        SkillSession dbSession = buildImDirectSession(sessionIdLong, "owner-1", "assist-1", "biz-group-1");
        dbSession.setAk("ak-1");
        when(sessionService.getSession(sessionIdLong)).thenReturn(dbSession);

        // pending list 已空 → 进入 DB 反查
        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(Collections.emptyList());

        // DB 返一条历史 user message
        SkillMessage lastUserMsg = new SkillMessage();
        lastUserMsg.setContent("历史消息");
        when(messageRepository.findLastUserMessage(sessionIdLong)).thenReturn(lastUserMsg);

        CapturingCallback cb = new CapturingCallback();
        service.handleSessionNotFound(sessionId, "owner-1", cb);

        // 验证 rightPush 被调用入 PendingChatRequest JSON（含完整字段反查）
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations, atLeastOnce()).rightPush(eq(key), raw.capture());
        // 反序列化最后一次入队的 entry，检查 fromSessionFallback 反查字段
        String json = raw.getAllValues().get(raw.getAllValues().size() - 1);
        try {
            PendingChatRequest req = objectMapper.readValue(json, PendingChatRequest.class);
            assertEquals("历史消息", req.text());
            assertEquals("assist-1", req.assistantAccount(),
                    "PR3: rebuild_from_db 反查 session.assistantAccount");
            assertEquals("owner-1", req.sendUserAccount(),
                    "PR3: rebuild_from_db 反查 session.userId（owner）");
            assertNull(req.imGroupId(),
                    "PR3: direct session fallback 不反查 imGroupId");
            // businessExtParam fields_degraded — Java null 经 Jackson 反序列化变 NullNode（PR1 边界）
            assertTrue(req.businessExtParam() == null || req.businessExtParam().isNull(),
                    "PR3: businessExtParam fields_degraded（Java null 或 NullNode 均接受）");
        } catch (Exception e) {
            fail("PendingChatRequest deserialize 失败: " + e.getMessage());
        }

        // 检查 sessionService.clearToolSessionId 被调用
        verify(sessionService).clearToolSessionId(sessionIdLong);
    }

    @Test
    @DisplayName("PR3: rebuildFromStoredUserMessage fromSessionFallback IAE 兜底 → 不 append + ERROR 日志")
    void rebuildFromStoredUserMessage_fallbackIae_doesNotAppend() {
        String sessionId = "6002";
        String key = "ss:pending-rebuild:" + sessionId;
        Long sessionIdLong = 6002L;
        stubRedisCounter(sessionId);

        when(valueOperations.setIfAbsent(eq("ss:rebuild-lock:" + sessionId), anyString(), any()))
                .thenReturn(true);

        // session.userId == null → fromSessionFallback 会抛 IAE
        SkillSession brokenSession = new SkillSession();
        brokenSession.setId(sessionIdLong);
        brokenSession.setUserId(null);
        brokenSession.setAssistantAccount("assist-broken");
        brokenSession.setAk("ak-broken");
        brokenSession.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        brokenSession.setBusinessSessionType(SkillSession.SESSION_TYPE_DIRECT);
        when(sessionService.getSession(sessionIdLong)).thenReturn(brokenSession);

        when(listOperations.range(eq(key), eq(0L), eq(-1L))).thenReturn(Collections.emptyList());

        SkillMessage lastUserMsg = new SkillMessage();
        lastUserMsg.setContent("缺 userId 的消息");
        when(messageRepository.findLastUserMessage(sessionIdLong)).thenReturn(lastUserMsg);

        CapturingCallback cb = new CapturingCallback();
        service.handleSessionNotFound(sessionId, "owner-x", cb);

        // 关键：IAE 兜底后不应 rightPush 任何 entry
        verify(listOperations, never()).rightPush(eq(key), anyString());

        // 但 sessionService.clearToolSessionId + sendInvoke create_session 仍要执行（rebuild 链路继续）
        verify(sessionService).clearToolSessionId(sessionIdLong);
        // create_session invoke 仍发送（让 retry 路径有机会自愈）
        assertEquals(1, cb.invokes.size(), "fallback IAE 后仍发 create_session 给 Gateway");
        assertEquals(GatewayActions.CREATE_SESSION, cb.invokes.get(0).action());
    }

    @Test
    @DisplayName("PR3: rebuildFromStoredUserMessage Redis pending 已有 → 跳过 DB 查询")
    void rebuildFromStoredUserMessage_redisHasPending_skipsDbLookup() throws Exception {
        String sessionId = "6003";
        String key = "ss:pending-rebuild:" + sessionId;
        Long sessionIdLong = 6003L;
        stubRedisCounter(sessionId);

        when(valueOperations.setIfAbsent(eq("ss:rebuild-lock:" + sessionId), anyString(), any()))
                .thenReturn(true);

        SkillSession dbSession = buildImGroupSession(sessionIdLong, "owner-3", "assist-3", "biz-group-3");
        dbSession.setAk("ak-3");
        when(sessionService.getSession(sessionIdLong)).thenReturn(dbSession);

        // pending list 已经有一条
        PendingChatRequest existing = new PendingChatRequest(
                "已有消息", "assist-3", "owner-3", "biz-group-3", "msg-pre-existing", null, "im", "group");
        when(listOperations.range(eq(key), eq(0L), eq(-1L)))
                .thenReturn(List.of(objectMapper.writeValueAsString(existing)));

        CapturingCallback cb = new CapturingCallback();
        service.handleSessionNotFound(sessionId, "owner-3", cb);

        // 关键：findLastUserMessage 不应被调用（已有 pending → 跳过 DB）
        verify(messageRepository, never()).findLastUserMessage(any());
    }

    // ==================== PR3: rebuildToolSession 老 String 重载 fallback ====================

    @Test
    @DisplayName("PR3: rebuildToolSession 老 String 重载 fromSessionFallback 成功 → 入队半填 entry + WARN 日志")
    void rebuildToolSession_legacyStringOverload_fallbackSuccess_appendsPending() {
        String sessionId = "7001";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession fullSession = buildImDirectSession(7001L, "owner-7", "assist-7", "biz-group-7");
        fullSession.setAk("ak-7");

        CapturingCallback cb = new CapturingCallback();
        // 调老 String 重载 — 内部应自动 fromSessionFallback + appendPendingMessage
        service.rebuildToolSession(sessionId, fullSession, "降级文本", cb);

        // 验证 rightPush 入队（fromSessionFallback 成功路径）
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        try {
            PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
            assertEquals("降级文本", req.text());
            assertEquals("assist-7", req.assistantAccount());
            assertEquals("owner-7", req.sendUserAccount());
            assertNull(req.imGroupId());
            // businessExtParam fields_degraded（Java null 反序列化变 NullNode）
            assertTrue(req.businessExtParam() == null || req.businessExtParam().isNull());
        } catch (Exception e) {
            fail("反序列化失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("PR3: rebuildToolSession 老 String 重载 fromSessionFallback IAE → 仍入半填 entry（不静默丢消息）")
    void rebuildToolSession_legacyStringOverload_fallbackIae_appendsHalfFilled() {
        String sessionId = "7002";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        // assistantAccount 缺失 → fromSessionFallback 会抛 IAE
        SkillSession brokenSession = new SkillSession();
        brokenSession.setId(7002L);
        brokenSession.setUserId("owner-broken");
        brokenSession.setAssistantAccount(null);
        brokenSession.setAk("ak-broken");
        brokenSession.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        brokenSession.setBusinessSessionType(SkillSession.SESSION_TYPE_DIRECT);

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, brokenSession, "IAE 兜底文本", cb);

        // 仍入队（半填 entry）— 不静默丢消息
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        try {
            PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
            assertEquals("IAE 兜底文本", req.text());
            assertNull(req.assistantAccount(), "IAE 后 assistantAccount 必须为 null（让 critical_field_missing 日志暴露）");
            assertNull(req.sendUserAccount());
            assertNull(req.imGroupId());
            assertNotNull(req.messageId(), "messageId 仍有值（System.currentTimeMillis）");
        } catch (Exception e) {
            fail("反序列化失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("PR3: rebuildToolSession 老 String 重载 pendingMessage=null → 不调用 appendPendingMessage")
    void rebuildToolSession_legacyStringOverload_nullPendingMessage_skipsAppend() {
        String sessionId = "7003";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImGroupSession(7003L, "owner-9", "assist-9", "biz-group-9");
        session.setAk("ak-9");

        CapturingCallback cb = new CapturingCallback();
        // 显式选老 String 重载
        service.rebuildToolSession(sessionId, session, (String) null, cb);

        verify(listOperations, never()).rightPush(eq(key), anyString());
        // 但 create_session 仍要发
        assertEquals(1, cb.invokes.size());
        assertEquals(GatewayActions.CREATE_SESSION, cb.invokes.get(0).action());
    }

    // ==================== PR3: rebuildToolSession 新 PendingChatRequest 重载 ====================

    @Test
    @DisplayName("PR3: rebuildToolSession 新 PendingChatRequest 重载 → 直接入队，不走 fallback")
    void rebuildToolSession_newOverload_appendsPendingChatRequest() throws Exception {
        String sessionId = "7004";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImGroupSession(7004L, "owner-10", "assist-10", "biz-group-10");
        session.setAk("ak-10");

        JsonNode ext = objectMapper.readTree("{\"topicId\":99}");
        PendingChatRequest req = new PendingChatRequest(
                "完整 PendingChatRequest", "assist-10", "real-sender-10",
                "biz-group-10", "msg-pr3", ext, "im", "group");

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, req, cb);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest got = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("完整 PendingChatRequest", got.text());
        assertEquals("real-sender-10", got.sendUserAccount(),
                "新签名直接使用入参 PendingChatRequest, 不走 fromSessionFallback");
        assertEquals(99, got.businessExtParam().get("topicId").asInt());
    }

    @Test
    @DisplayName("personal create_session 下发复用 PendingChatRequest.allowedSlashCommands")
    void rebuildToolSession_newOverload_createSessionCarriesAllowedSlashCommands() {
        String sessionId = "7007";
        stubRedisCounter(sessionId);

        SkillSession session = buildImDirectSession(7007L, "owner-13", "assist-13", "biz-direct-13");
        session.setAk("ak-13");

        List<String> allowedSlashCommands = List.of("new", "sessions", "session", "models");
        PendingChatRequest req = new PendingChatRequest(
                "首条消息", "assist-13", "sender-13",
                null, "msg-13", null, "im", "direct",
                null, allowedSlashCommands);

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, req, cb);

        assertEquals(1, cb.invokes.size());
        InvokeCommand command = cb.invokes.get(0);
        assertEquals(GatewayActions.CREATE_SESSION, command.action());
        assertEquals("im", command.domain());
        assertEquals("direct", command.domainType());
        assertEquals("biz-direct-13", command.businessSessionId());
        assertEquals(allowedSlashCommands, command.allowedSlashCommands());
    }

    @Test
    @DisplayName("PR3: rebuildToolSession 新重载 pendingRequest=null → 不调用 appendPendingMessage")
    void rebuildToolSession_newOverload_nullPendingRequest_skipsAppend() {
        String sessionId = "7005";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImGroupSession(7005L, "owner-11", "assist-11", "biz-group-11");
        session.setAk("ak-11");

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, (PendingChatRequest) null, cb);

        verify(listOperations, never()).rightPush(eq(key), anyString());
        // 但 create_session 仍要发
        assertEquals(1, cb.invokes.size());
        assertEquals(GatewayActions.CREATE_SESSION, cb.invokes.get(0).action());
    }

    @Test
    @DisplayName("create_session uses routeUserId when group session.userId is null")
    void rebuildToolSession_routeUserIdOverridesNullSessionUserId() {
        String sessionId = "7006";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImGroupSession(7006L, null, "assist-12", "biz-group-12");
        session.setAk("ak-12");

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, (PendingChatRequest) null, "owner-route-12", cb);

        verify(listOperations, never()).rightPush(eq(key), anyString());
        assertEquals(1, cb.invokes.size());
        assertEquals(GatewayActions.CREATE_SESSION, cb.invokes.get(0).action());
        assertEquals("owner-route-12", cb.invokes.get(0).userId());
    }

    // ==================== v3 allowed-slash-commands: legacy String overload personal scope gating ====================

    @Test
    @DisplayName("v3 AC13: legacy String overload / personal scope + sysconfig 命中 → list 写入 pending entry")
    void rebuildToolSession_legacyStringOverload_personalScope_writesAllowedSlash() throws Exception {
        String sessionId = "8001";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImDirectSession(8001L, "owner-v3-1", "assist-v3-1", "biz-v3-1");
        session.setAk("ak-v3-1");
        // sysconfig 命中：personal scope strategy.generateToolSessionId() == null + resolver 返 list
        lenient().when(allowedSlashCommandsResolver.resolve("im", "direct"))
                .thenReturn(java.util.List.of("plan", "ask"));

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, "personal scope text", cb);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("personal scope text", req.text());
        assertNotNull(req.allowedSlashCommands(), "personal scope 命中 sysconfig → 写入 list");
        assertEquals(2, req.allowedSlashCommands().size());
        assertEquals("plan", req.allowedSlashCommands().get(0));
        assertEquals("ask", req.allowedSlashCommands().get(1));
    }

    @Test
    @DisplayName("v3 AC14: legacy String overload / business self-heal scope（strategy 返非 null）→ 不调 resolver + entry 不含 list")
    void rebuildToolSession_legacyStringOverload_businessScope_skipsResolver() throws Exception {
        String sessionId = "8002";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImDirectSession(8002L, "owner-v3-2", "assist-v3-2", "biz-v3-2");
        session.setAk("ak-v3-2");

        // business scope: strategy.generateToolSessionId() != null
        AssistantScopeStrategy businessStrategy = org.mockito.Mockito.mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.generateToolSessionId()).thenReturn("cloud-pre-gen");
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(businessStrategy);

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, "business self-heal text", cb);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("business self-heal text", req.text());
        assertNull(req.allowedSlashCommands(), "business scope → 不下发 + entry 不含 list");
        // resolver 不被 invoke（business scope 零次 sysconfig 查询）
        verify(allowedSlashCommandsResolver, never())
                .resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("v3: legacy String overload / personal + resolver 返 null（未配置）→ entry allowedSlashCommands=null")
    void rebuildToolSession_legacyStringOverload_personalScopeNoConfig_entryListNull() throws Exception {
        String sessionId = "8003";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImDirectSession(8003L, "owner-v3-3", "assist-v3-3", "biz-v3-3");
        session.setAk("ak-v3-3");
        // resolver 默认返 null（与 setUp 一致），不需 stub

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, "no config text", cb);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("no config text", req.text());
        assertNull(req.allowedSlashCommands());
    }

    @Test
    @DisplayName("v3: legacy String overload / scope gating 异常 → 降级 list=null（不阻塞 rebuild 主流程）")
    void rebuildToolSession_legacyStringOverload_scopeGatingException_degradesToNull() throws Exception {
        String sessionId = "8004";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        SkillSession session = buildImDirectSession(8004L, "owner-v3-4", "assist-v3-4", "biz-v3-4");
        session.setAk("ak-v3-4");

        // scopeDispatcher 抛异常（模拟下游 service 故障）
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class)))
                .thenThrow(new RuntimeException("simulated scope dispatcher failure"));

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, session, "scope exception text", cb);

        // rebuild 主流程不应被阻塞
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("scope exception text", req.text());
        assertNull(req.allowedSlashCommands(), "scope gating 异常时降级为 null");

        // create_session 仍要发
        assertEquals(1, cb.invokes.size());
        assertEquals(GatewayActions.CREATE_SESSION, cb.invokes.get(0).action());
    }

    @Test
    @DisplayName("v3: legacy String overload / IAE 兜底 → entry allowedSlashCommands=null（保守降级）")
    void rebuildToolSession_legacyStringOverload_iaeFallback_listNull() throws Exception {
        String sessionId = "8005";
        String key = "ss:pending-rebuild:" + sessionId;
        stubRedisCounter(sessionId);

        // assistantAccount 缺失触发 IAE
        SkillSession brokenSession = new SkillSession();
        brokenSession.setId(8005L);
        brokenSession.setUserId("owner-iae");
        brokenSession.setAssistantAccount(null);
        brokenSession.setAk("ak-iae");
        brokenSession.setBusinessSessionDomain(SkillSession.DOMAIN_IM);
        brokenSession.setBusinessSessionType(SkillSession.SESSION_TYPE_DIRECT);

        lenient().when(allowedSlashCommandsResolver.resolve("im", "direct"))
                .thenReturn(java.util.List.of("plan"));

        CapturingCallback cb = new CapturingCallback();
        service.rebuildToolSession(sessionId, brokenSession, "iae text", cb);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq(key), raw.capture());
        PendingChatRequest req = objectMapper.readValue(raw.getValue(), PendingChatRequest.class);
        assertEquals("iae text", req.text());
        // B4 IAE 兜底分支保守传 null（即使 resolver 有 list 也不写）
        assertNull(req.allowedSlashCommands(), "IAE 兜底分支应保守传 null");
    }
}
