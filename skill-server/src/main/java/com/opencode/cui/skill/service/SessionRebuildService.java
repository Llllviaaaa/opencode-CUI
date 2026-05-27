package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话重建服务。
 * 当工具会话（toolSession）过期、上下文溢出或找不到时,
 * 自动触发重建流程：通知前端重试状态 → 清除旧会话 → 向 Gateway 发送 create_session 命令。
 *
 * <p>
 * 使用 Redis List 暂存待重建的用户消息（支持多实例 + 群聊多人并发）,
 * 重建完成后按 FIFO 顺序逐条重试发送。
 * </p>
 *
 * <p>
 * <strong>PR2 升级</strong>：pending list 的 entry value 升级为 JSON 序列化的
 * {@link PendingChatRequest} 结构体（含 text + sender / assistant / imGroupId / messageId
 * / businessExtParam 6 字段）。{@link #appendPendingMessage(String, PendingChatRequest)} 入队
 * JSON, {@link #consumePendingRequests(String)} 反序列化为 PendingChatRequest 列表。
 * </p>
 *
 * <p>
 * <strong>向后兼容</strong>：PR3 已删除老 {@code appendPendingMessage(String, String)} 和
 * {@code consumePendingMessages(String) -> List<String>} 重载；仅保留
 * {@link #peekPendingMessages(String)} 与 {@link #rebuildToolSession(String, SkillSession, String, RebuildCallback)}
 * 老 String 重载作 API 兼容层（{@code @Deprecated} 标记 TODO follow-up 清理）。
 * 老格式 plain-string entry 或对抗输入（如 {@code {"foo":"bar"}}）在 consume 时通过
 * {@code fromSessionFallback} 兜底为半填 PendingChatRequest, 保留消息内容不丢失。
 * 详情参见 PRD §Requirements 2 + Codex Review M1。
 * </p>
 */
@Slf4j
@Service
public class SessionRebuildService {

    /** 待重建消息 Redis key 前缀：ss:pending-rebuild:{sessionId} → List of JSON(PendingChatRequest) */
    private static final String PENDING_MSG_PREFIX = "ss:pending-rebuild:";
    /** 重建计数器 Redis key 前缀：ss:rebuild-counter:{sessionId} → 已重建次数 */
    private static final String REBUILD_COUNTER_PREFIX = "ss:rebuild-counter:";
    /** 待重建消息 TTL */
    private static final Duration PENDING_MSG_TTL = Duration.ofMinutes(5);
    /** 重建分布式锁 Redis key 前缀：ss:rebuild-lock:{sessionId} */
    private static final String REBUILD_LOCK_PREFIX = "ss:rebuild-lock:";
    /** 重建分布式锁 TTL */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;
    private final StringRedisTemplate redisTemplate;
    private final AllowedSlashCommandsResolver allowedSlashCommandsResolver;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final int maxRebuildAttempts;
    private final int rebuildCooldownSeconds;

    public SessionRebuildService(ObjectMapper objectMapper,
            SkillSessionService sessionService,
            SkillMessageRepository messageRepository,
            StringRedisTemplate redisTemplate,
            AllowedSlashCommandsResolver allowedSlashCommandsResolver,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-max-attempts:3}") int maxRebuildAttempts,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-cooldown-seconds:30}") int rebuildCooldownSeconds) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.redisTemplate = redisTemplate;
        this.allowedSlashCommandsResolver = allowedSlashCommandsResolver;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.maxRebuildAttempts = maxRebuildAttempts;
        this.rebuildCooldownSeconds = rebuildCooldownSeconds;
    }

    /** 处理工具会话不存在的情况，触发从存储消息重建。 */
    public void handleSessionNotFound(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Tool session not found for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    /** 处理上下文溢出的情况，清除旧会话并触发重建。 */
    public void handleContextOverflow(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Context overflow for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    /**
     * 执行工具会话重建核心流程（PR3 新签名，接收完整 {@link PendingChatRequest}）。
     * <ol>
     * <li>检查重建计数器是否超限</li>
     * <li>缓存待重试的用户消息到 Redis List（追加，不覆盖）— 用新 API
     *     {@link #appendPendingMessage(String, PendingChatRequest)}</li>
     * <li>广播 retry 状态到前端</li>
     * <li>向 Gateway 发送 create_session 命令</li>
     * </ol>
     *
     * @param pendingRequest 完整结构化的 pending 消息（含 sender / assistantAccount / imGroupId /
     *                       businessExtParam）；为 null 或 text 空白时跳过 appendPendingMessage。
     */
    public void rebuildToolSession(String sessionId, SkillSession session,
            PendingChatRequest pendingRequest, RebuildCallback callback) {
        rebuildToolSession(sessionId, session, pendingRequest, null, callback);
    }

    public void rebuildToolSession(String sessionId, SkillSession session,
            PendingChatRequest pendingRequest, String routeUserId, RebuildCallback callback) {
        // --- 重建计数器检查（Redis 全局共享，多实例一致） ---
        int attempts = incrementRebuildCounter(sessionId);

        if (attempts > maxRebuildAttempts) {
            log.warn("Rebuild exhausted: session={}, attempts={}, cooldownSeconds={}",
                    sessionId, attempts, rebuildCooldownSeconds);
            clearPendingMessages(sessionId);
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                sessionService.clearToolSessionId(numericId);
            }
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("会话连接异常（重建已达上限），请等待 "
                            + rebuildCooldownSeconds + " 秒后重试"));
            return;
        }

        log.info("Rebuild attempt {}/{} for session={}", attempts, maxRebuildAttempts, sessionId);

        // --- 缓存待重试消息到 Redis List（追加，支持群聊多人并发） ---
        // PR3：用新 API 入队完整 PendingChatRequest；text 为空 / pendingRequest=null 时跳过。
        if (pendingRequest != null && pendingRequest.text() != null && !pendingRequest.text().isBlank()) {
            appendPendingMessage(sessionId, pendingRequest);
            log.info("Appended pending retry message for session {}: text='{}', assistantAccount={}, sender={}, imGroupId={}",
                    sessionId,
                    pendingRequest.text().substring(0, Math.min(50, pendingRequest.text().length())),
                    pendingRequest.assistantAccount(),
                    pendingRequest.sendUserAccount(),
                    pendingRequest.imGroupId());
        }

        callback.broadcast(sessionId, session.getUserId(), StreamMessage.sessionStatus("retry"));

        if (session.getAk() == null || session.getAk().isBlank()) {
            log.error("Cannot rebuild session {}: no ak associated", sessionId);
            clearPendingMessages(sessionId);
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("AI session expired and cannot be rebuilt"));
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", session.getTitle() != null ? session.getTitle() : "");
        String payloadStr;
        try {
            payloadStr = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            payloadStr = "{}";
        }

        callback.sendInvoke(new InvokeCommand(
                session.getAk(),
                ProtocolUtils.firstNonBlank(routeUserId, session.getUserId()),
                sessionId,
                GatewayActions.CREATE_SESSION,
                payloadStr,
                null,
                session.getBusinessSessionDomain(),
                session.getBusinessSessionType(),
                session.getBusinessSessionId(),
                pendingRequest != null ? pendingRequest.allowedSlashCommands() : null));
        log.info("Rebuild create_session sent for welinkSession={}, ak={}", sessionId, session.getAk());
    }

    /**
     * 执行工具会话重建核心流程（老 String 入参重载）。
     *
     * <p>PR3 改造：本重载内部委托给 {@link #rebuildToolSession(String, SkillSession,
     * PendingChatRequest, RebuildCallback)} 新签名。
     * <ul>
     *   <li>{@code pendingMessage == null/blank} → 传 {@code pendingRequest = null}</li>
     *   <li>非空 → 尝试构造 {@link PendingChatRequest#fromSessionFallback}；成功记 WARN
     *       (fields_degraded=businessExtParam)，失败（IAE）记 ERROR 但仍传半填 entry 给下游</li>
     * </ul>
     *
     * @deprecated PR3 caller 应直接使用新签名 + {@link PendingChatRequest}。
     *             保留是因为 {@code GatewayRelayService.rebuildToolSession(String, SkillSession, String)}
     *             公共委托还在被 {@code SkillMessageController.routeToGateway} 等使用。
     *             follow-up 清理时同步移除。
     */
    @Deprecated
    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, RebuildCallback callback) {
        rebuildToolSession(sessionId, session, pendingMessage, null, callback);
    }

    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, String routeUserId, RebuildCallback callback) {
        PendingChatRequest pendingRequest = null;
        if (pendingMessage != null && !pendingMessage.isBlank()) {
            // v3 allowed-slash-commands: personal scope gating
            //   legacy String overload 不止被 miniapp first（personal）调用，
            //   还被 IM/External case B personal + business self-heal fallback 调用。
            //   business self-heal 进入此处时 isPersonalScope=false → list=null → 写入 pending 的 entry
            //   allowedSlashCommands=null，retry 下发不含该 key。双重保险（与 ImSessionManager:163 business
            //   即时分支 + BusinessScopeStrategy 4 参 builder 协同）。
            List<String> allowedSlashCommands = null;
            try {
                if (session != null && session.getAk() != null) {
                    AssistantInfo info = assistantInfoService.getAssistantInfo(session.getAk());
                    boolean isPersonalScope = scopeDispatcher.getStrategy(info).generateToolSessionId() == null;
                    if (isPersonalScope) {
                        allowedSlashCommands = allowedSlashCommandsResolver.resolve(
                                session.getBusinessSessionDomain(),
                                session.getBusinessSessionType());
                    }
                }
            } catch (RuntimeException e) {
                // assistantInfoService / scopeDispatcher 异常不能阻塞 rebuild 主流程：降级为不下发。
                log.warn("[WARN] rebuildToolSession(legacy String overload): personal scope gating failed, sessionId={}, error={}",
                        sessionId, e.getMessage());
                allowedSlashCommands = null;
            }
            try {
                pendingRequest = PendingChatRequest.fromSessionFallback(session, pendingMessage, allowedSlashCommands);
                log.warn("[WARN] rebuildToolSession(legacy String overload): reason=rebuild_legacy_string_overload, fields_degraded=businessExtParam, sessionId={}, assistantAccount={}, sender={}, imGroupId={}, hasAllowedSlash={}",
                        sessionId, pendingRequest.assistantAccount(),
                        pendingRequest.sendUserAccount(), pendingRequest.imGroupId(),
                        allowedSlashCommands != null);
            } catch (IllegalArgumentException iae) {
                log.error("[ERROR] rebuildToolSession(legacy String overload): reason=fallback_account_missing_on_legacy_overload, sessionId={}, error={}",
                        sessionId, iae.getMessage());
                // B4 IAE 兜底：保守传 null（degraded path），保持 8 参 secondary（PRD 决策）
                pendingRequest = new PendingChatRequest(
                        pendingMessage, null, null, null,
                        String.valueOf(System.currentTimeMillis()), null,
                        null, null);
            }
        }
        rebuildToolSession(sessionId, session, pendingRequest, routeUserId, callback);
    }

    // ==================== 新签名（PR2） ====================

    /**
     * 追加结构化 pending 消息到 Redis List（PR2 新签名）。
     *
     * <p>{@link PendingChatRequest} 会被 JSON 序列化后入队, consume 时反序列化拿回 6 字段
     * （text / assistantAccount / sendUserAccount / imGroupId / messageId / businessExtParam）,
     * 由 {@code GatewayMessageRouter.retryPendingMessages} 重建完整 chat invoke payload。
     *
     * <p>序列化失败时降级为只缓存 text（保留消息内容不丢失），并记 ERROR 日志。
     *
     * @param sessionId 会话 ID（Skill 主键字符串）
     * @param request   结构化 pending 消息（非 null）
     */
    public void appendPendingMessage(String sessionId, PendingChatRequest request) {
        if (request == null) {
            log.warn("[SKIP] appendPendingMessage: reason=null_request, sessionId={}", sessionId);
            return;
        }
        String key = PENDING_MSG_PREFIX + sessionId;
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            // 序列化不应该失败（PendingChatRequest 是 record + Jackson 注解齐全），
            // 但万一失败：降级到只缓存 text，保证消息不丢失。
            log.error("[ERROR] appendPendingMessage: 序列化失败, 降级到 plain-text, sessionId={}, error={}",
                    sessionId, e.getMessage());
            serialized = request.text() != null ? request.text() : "";
        }
        try {
            redisTemplate.opsForList().rightPush(key, serialized);
            redisTemplate.expire(key, PENDING_MSG_TTL);
        } catch (Exception e) {
            log.error("[ERROR] appendPendingMessage: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 消费所有 pending 消息（PR2 新签名），消费后从 Redis 中移除。
     *
     * <p>反序列化策略（采纳 Codex M1）：
     * <ol>
     *   <li>{@code mapper.readTree(rawValue)} 失败 → 当老格式 plain-text, 走
     *       {@link PendingChatRequest#fromSessionFallback}；记 {@code pending_format=fallback_invalid_json}</li>
     *   <li>{@code readTree} 成功但不是 object（如纯字符串 / array / number）→ 当 plain-text
     *       fallback；记 {@code pending_format=plain}</li>
     *   <li>是 object 但 {@code text} 字段非"非空 textual" → 当 plain-text fallback
     *       （防对抗输入 {@code {"foo":"bar"}}）；记 {@code pending_format=plain}</li>
     *   <li>否则按新格式 {@code mapper.treeToValue(node, PendingChatRequest.class)};
     *       记 {@code pending_format=json}</li>
     * </ol>
     *
     * <p>fromSessionFallback 在 session 不存在 / IAE 时不静默吞消息：返回 fallback
     * {@code PendingChatRequest(text=rawText, 其他 null)} 让 retry 路径自行决定降级行为。
     *
     * @return FIFO 顺序的结构化 pending 消息列表；空列表表示无待发消息
     */
    public List<PendingChatRequest> consumePendingRequests(String sessionId) {
        String key = PENDING_MSG_PREFIX + sessionId;
        List<String> rawMessages;
        try {
            rawMessages = redisTemplate.opsForList().range(key, 0, -1);
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("[ERROR] consumePendingRequests: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return Collections.emptyList();
        }
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Collections.emptyList();
        }

        // 反查 session 1 次, 多条 entry 走 fallback 时复用
        SkillSession sessionForFallback = loadSessionForFallbackSafely(sessionId);

        List<PendingChatRequest> result = new ArrayList<>(rawMessages.size());
        int jsonCount = 0;
        int plainCount = 0;
        int invalidJsonCount = 0;
        for (String rawValue : rawMessages) {
            ParseOutcome outcome = parsePendingEntry(sessionId, rawValue, sessionForFallback);
            if (outcome.request != null) {
                result.add(outcome.request);
            }
            switch (outcome.format) {
                case JSON -> jsonCount++;
                case PLAIN -> plainCount++;
                case FALLBACK_INVALID_JSON -> invalidJsonCount++;
            }
        }

        log.info("[EXIT] consumePendingRequests: sessionId={}, total={}, json_count={}, plain_count={}, fallback_invalid_json_count={}",
                sessionId, result.size(), jsonCount, plainCount, invalidJsonCount);
        return result;
    }

    /**
     * 查看（不消费）pending 消息列表（PR2 新签名）。
     *
     * <p>语义与 {@link #consumePendingRequests(String)} 相同, 但不删除 Redis key。
     * 用于 {@code rebuildFromStoredUserMessage} 判断是否需要从 DB 补充消息。
     *
     * @return 当前 FIFO 顺序的结构化 pending 消息列表（只读）
     */
    public List<PendingChatRequest> peekPendingRequests(String sessionId) {
        String key = PENDING_MSG_PREFIX + sessionId;
        List<String> rawMessages;
        try {
            rawMessages = redisTemplate.opsForList().range(key, 0, -1);
        } catch (Exception e) {
            log.warn("[WARN] peekPendingRequests: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return Collections.emptyList();
        }
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Collections.emptyList();
        }

        SkillSession sessionForFallback = loadSessionForFallbackSafely(sessionId);
        List<PendingChatRequest> result = new ArrayList<>(rawMessages.size());
        for (String rawValue : rawMessages) {
            ParseOutcome outcome = parsePendingEntry(sessionId, rawValue, sessionForFallback);
            if (outcome.request != null) {
                result.add(outcome.request);
            }
        }
        return result;
    }

    // ==================== 老签名（@Deprecated, 仅保留 peek，其它 PR3 已删除） ====================

    /**
     * 查看（不消费）pending 消息列表并返回 <strong>plain text 列表</strong>（老签名）。
     *
     * <p>PR3 后无内部 caller — {@code rebuildFromStoredUserMessage} 已切到
     * {@link #peekPendingRequests(String)} 新签名。本方法仍保留作 API 兼容层,
     * 等所有外部 caller 切换完成后可一并删除。
     *
     * @deprecated TODO follow-up: remove after all callers migrate to PendingChatRequest API.
     *             目前可由 {@link #peekPendingRequests(String)} 配合 {@code .size() == 0} 替代。
     */
    @Deprecated
    public List<String> peekPendingMessages(String sessionId) {
        List<PendingChatRequest> requests = peekPendingRequests(sessionId);
        if (requests.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> texts = new ArrayList<>(requests.size());
        for (PendingChatRequest req : requests) {
            texts.add(req.text());
        }
        return texts;
    }

    /** 清除会话的所有待重建消息。 */
    public void clearPendingMessages(String sessionId) {
        String key = PENDING_MSG_PREFIX + sessionId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[WARN] clearPendingMessages: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /** 从数据库中获取最近的用户消息并触发重建。加分布式锁防止并发重复重建。 */
    private void rebuildFromStoredUserMessage(String sessionId, RebuildCallback callback) {
        Long sessionIdLong = ProtocolUtils.parseSessionId(sessionId);
        if (sessionIdLong == null) {
            log.error("Failed to rebuild session {}: invalid sessionId", sessionId);
            return;
        }

        // --- 分布式锁：防止并发请求同时触发重建 ---
        String lockKey = REBUILD_LOCK_PREFIX + sessionId;
        String lockValue = java.util.UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, REBUILD_LOCK_TTL));
        } catch (Exception e) {
            log.warn("[WARN] rebuildFromStoredUserMessage: 获取锁失败, 降级放行, sessionId={}, error={}",
                    sessionId, e.getMessage());
            locked = true; // Redis 故障时降级放行，允许单次重建
        }

        if (!locked) {
            log.info("Rebuild already in progress for session={}, skipping (message in pending list)", sessionId);
            return;
        }

        try {
            SkillSession session = sessionService.getSession(sessionIdLong);
            sessionService.clearToolSessionId(sessionIdLong);

            // 如果 Redis List 已有消息（来自 Case C 预缓存），跳过 DB 查询，避免重复追加
            // PR3: 切到 peekPendingRequests 新 API；这里只需要 size > 0 判断
            PendingChatRequest pendingRequest = null;
            List<PendingChatRequest> existingPending = peekPendingRequests(sessionId);
            if (existingPending.isEmpty()) {
                SkillMessage lastUserMsg = messageRepository.findLastUserMessage(sessionIdLong);
                if (lastUserMsg != null && lastUserMsg.getContent() != null) {
                    // PR3 fallback: 用 fromSessionFallback 构造完整 PendingChatRequest;
                    // session.assistantAccount / userId 任一缺失 → IAE → ERROR 日志 + pendingRequest 留 null
                    // （rebuildToolSession 内会跳过 appendPendingMessage，由 retry 路径暴露问题）
                    try {
                        pendingRequest = PendingChatRequest.fromSessionFallback(session, lastUserMsg.getContent());
                        log.warn("[WARN] rebuildFromStoredUserMessage: reason=rebuild_from_db, fields_degraded=businessExtParam, sessionId={}, assistantAccount={}, senderFromOwner={}, imGroupId={}",
                                sessionId, pendingRequest.assistantAccount(),
                                pendingRequest.sendUserAccount(), pendingRequest.imGroupId());
                    } catch (IllegalArgumentException iae) {
                        log.error("[ERROR] rebuildFromStoredUserMessage: reason=fallback_account_missing_on_db_rebuild, sessionId={}, error={}",
                                sessionId, iae.getMessage());
                        // pendingRequest 留 null — rebuildToolSession 内不会 appendPendingMessage
                    }
                }
            } else {
                log.info("Pending messages already exist for session={}, count={}, skipping DB lookup",
                        sessionId, existingPending.size());
            }

            rebuildToolSession(sessionId, session, pendingRequest, callback);
        } catch (Exception e) {
            log.error("Failed to rebuild session {}: {}", sessionId, e.getMessage(), e);
            clearPendingMessages(sessionId);
        } finally {
            // 安全释放锁（仅释放自己持有的）
            if (locked) {
                try {
                    String currentValue = redisTemplate.opsForValue().get(lockKey);
                    if (lockValue.equals(currentValue)) {
                        redisTemplate.delete(lockKey);
                    }
                } catch (Exception e) {
                    log.warn("[WARN] rebuildFromStoredUserMessage: 释放锁失败, sessionId={}, error={}",
                            sessionId, e.getMessage());
                }
            }
        }
    }

    // ==================== Redis helpers ====================

    /**
     * 原子递增重建计数器并刷新 TTL（等效原 Caffeine expireAfterAccess 语义）。
     * Redis 失败时降级放行（返回 1），避免因 Redis 故障阻塞重建。
     */
    private int incrementRebuildCounter(String sessionId) {
        String key = REBUILD_COUNTER_PREFIX + sessionId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(rebuildCooldownSeconds));
            return count != null ? count.intValue() : 1;
        } catch (Exception e) {
            log.warn("[WARN] incrementRebuildCounter: Redis 操作失败, 降级放行, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return 1;
        }
    }

    // ==================== pending parse helpers (PR2) ====================

    /** consume / peek 反序列化路径的分支标记，用于结构化日志。 */
    private enum PendingFormat {
        /** rawValue 是合法 JSON object 且 text 字段为非空 textual：按新格式反序列化成功 */
        JSON,
        /** rawValue 是合法 JSON 但不是 object schema, 或缺 text 字段：当 plain-text fallback */
        PLAIN,
        /** rawValue 不是合法 JSON：当 plain-text fallback（也是老格式残留 entry 的典型路径） */
        FALLBACK_INVALID_JSON
    }

    /** parsePendingEntry 返回组合: 解析后 PendingChatRequest + 标签格式（日志用）。 */
    private record ParseOutcome(PendingChatRequest request, PendingFormat format) {
    }

    /**
     * 把一条 Redis list 中的 raw value 解析为 {@link PendingChatRequest}：
     * <ul>
     *   <li>新格式（合法 JSON object + 非空 text 字段）→ 直接 deserialize</li>
     *   <li>老格式 / 对抗输入 / readTree 失败 → 走 {@code fromSessionFallback}, session 不存在或
     *       字段缺失时退化为半填（仅 text）entry, 保证消息内容不丢失</li>
     * </ul>
     */
    private ParseOutcome parsePendingEntry(String sessionId, String rawValue, SkillSession sessionForFallback) {
        if (rawValue == null) {
            // 防御：list 里出现 null（理论不该发生）→ 当空 plain
            return new ParseOutcome(buildPlainTextFallback(sessionId, "", sessionForFallback), PendingFormat.PLAIN);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(rawValue);
        } catch (JsonProcessingException e) {
            // readTree 失败 → rawValue 不是合法 JSON → 老格式 plain-text entry
            log.warn("[WARN] consumePending: pending_format=fallback_invalid_json, sessionId={}, length={}",
                    sessionId, rawValue.length());
            return new ParseOutcome(
                    buildPlainTextFallback(sessionId, rawValue, sessionForFallback),
                    PendingFormat.FALLBACK_INVALID_JSON);
        }

        // 严格 schema 判定（防对抗输入 {"foo":"bar"}：合法 JSON 但不是新格式）
        // 必须同时满足：是 object + text 字段为非空 textual
        if (node == null || !node.isObject()) {
            log.warn("[WARN] consumePending: pending_format=plain, reason=not_object, sessionId={}", sessionId);
            return new ParseOutcome(
                    buildPlainTextFallback(sessionId, rawValue, sessionForFallback),
                    PendingFormat.PLAIN);
        }
        JsonNode textNode = node.path("text");
        if (!textNode.isTextual() || textNode.asText().isEmpty()) {
            log.warn("[WARN] consumePending: pending_format=plain, reason=missing_text_field, sessionId={}", sessionId);
            return new ParseOutcome(
                    buildPlainTextFallback(sessionId, rawValue, sessionForFallback),
                    PendingFormat.PLAIN);
        }

        try {
            PendingChatRequest req = objectMapper.treeToValue(node, PendingChatRequest.class);
            log.debug("[EXIT] consumePending: pending_format=json, sessionId={}, messageId={}",
                    sessionId, req.messageId());
            return new ParseOutcome(req, PendingFormat.JSON);
        } catch (JsonProcessingException e) {
            // schema 看起来对但 treeToValue 失败（理论上不应发生）→ plain fallback
            log.warn("[WARN] consumePending: pending_format=plain, reason=tree_to_value_failed, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return new ParseOutcome(
                    buildPlainTextFallback(sessionId, rawValue, sessionForFallback),
                    PendingFormat.PLAIN);
        }
    }

    /**
     * plain-text entry 兜底为 {@link PendingChatRequest}：
     * <ul>
     *   <li>session 存在 + 字段齐全 → {@code fromSessionFallback(session, rawText)}, WARN
     *       {@code reason=plain_text_pending_fallback, fields_degraded=businessExtParam}</li>
     *   <li>session 存在但 assistantAccount / userId 缺失（fromSessionFallback 抛 IAE）→
     *       ERROR {@code reason=fallback_account_missing}, 返回半填 entry（仅 text）让上游决定降级</li>
     *   <li>session 不存在 → WARN {@code reason=session_not_found_for_plain_text_pending}, 返回半填 entry</li>
     * </ul>
     */
    private PendingChatRequest buildPlainTextFallback(String sessionId, String rawText, SkillSession session) {
        if (session == null) {
            log.warn("[WARN] buildPlainTextFallback: reason=session_not_found_for_plain_text_pending, sessionId={}",
                    sessionId);
            return new PendingChatRequest(rawText, null, null, null, null, null, null, null);
        }
        try {
            PendingChatRequest req = PendingChatRequest.fromSessionFallback(session, rawText);
            log.warn("[WARN] buildPlainTextFallback: reason=plain_text_pending_fallback, sessionId={}, fields_degraded=businessExtParam",
                    sessionId);
            return req;
        } catch (IllegalArgumentException iae) {
            // session.assistantAccount / userId 缺失 — 不静默 fast-fail, 仍把 entry 还给 retry 路径,
            // 由 retryPendingMessages 的 critical_field_missing ERROR 日志暴露问题。
            log.error("[ERROR] buildPlainTextFallback: reason=fallback_account_missing, sessionId={}, fields_degraded=skip, error={}",
                    sessionId, iae.getMessage());
            return new PendingChatRequest(rawText, null, null, null, null, null,
                    session.getBusinessSessionDomain(), session.getBusinessSessionType());
        }
    }

    /**
     * 安全反查 session 用于 fallback。findByIdSafe 是只读且非 null-throwing 的契约,
     * 但仍 catch 防御任何意外（如 sessionId 不可解析）。
     */
    private SkillSession loadSessionForFallbackSafely(String sessionId) {
        try {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId == null) {
                return null;
            }
            return sessionService.findByIdSafe(numericId);
        } catch (Exception e) {
            log.warn("[WARN] loadSessionForFallbackSafely: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 重建回调接口。
     * 由调用方实现，用于消息广播和命令发送。
     */
    public interface RebuildCallback {
        /** 向前端广播流式消息。 */
        void broadcast(String sessionId, String userId, StreamMessage msg);

        /** 向 Gateway 发送 invoke 命令。 */
        void sendInvoke(InvokeCommand command);
    }
}
