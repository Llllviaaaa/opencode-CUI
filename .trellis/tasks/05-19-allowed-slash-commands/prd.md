# allowed-slash-commands

> 个人助手 chat 场景下，从 sysconfig 拉 slash 命令白名单，下发到 `platformExtParam.allowedSlashCommands` 给 plugin。

> **v3**：吸收 codex (gpt-5.5 + xhigh) v2 评审（v1 6/6 FIXED；v2 新增 Critical=0 / Major=2 / Minor=1 → NEEDS_REVISION）。本版收口：保留所有现有 secondary constructor、加 personal scope gating、显式约束兼容性。**Critical=0 已过 gate（memory `feedback_codex_design_review`），用户决定直接开 PR1。**

> **v2**：吸收 codex v1 评审（Critical=2 / Major=4 / Minor=1 → BLOCK）。收口完整 callsite matrix、action guard、严格 string[] 校验、freeze 点精确、case B 入口显式列入、delete 缓存语义。

## Goal

让 plugin 在 chat 报文里拿到当前 session 维度（`businessSessionDomain` + `businessSessionType`）允许使用的 slash 命令清单。业务侧通过 sysconfig 配置即可动态生效（**update/create 立即 evict 缓存；delete 受 5min TTL 影响**，见已知风险 5），无需 plugin / skill-server 发版。

## 决策汇总（已与用户对齐）

| # | 决策项 | 决策 |
|---|---|---|
| 1 | sysconfig `configType` | `allowed_slash_commands` |
| 2 | sysconfig `configKey` | `${businessSessionDomain}_${businessSessionType}`（无前缀，下划线拼接） |
| 3 | sysconfig 存储格式 | JSON 数组字符串，如 `["plan","ask","run"]`（**严格 `string[]`，含非文本元素拒绝**） |
| 4 | platformExtParam 字段名 | `allowedSlashCommands`（camelCase） |
| 5 | 数据类型 | `List<String>` ↔ JSON array |
| 6 | 兜底 | sysconfig null / parse 失败 / 非数组 / **含非字符串元素** / domain 或 type 缺失 → **不下发该 key** |
| 7 | scope 范围 | **仅 personal scope**（business / default_assistant 不动） |
| 8 | first / retry 一致性 | **first 即时冻结**：入 pending 时取 → 写 `PendingChatRequest.allowedSlashCommands` → retry 复用 |
| 9 | helper 改造 | `PlatformExtParamBuilder.build` 加 5 参重载；4 参签名保留 |
| **10** | **action guard**（v2 新增）| **仅 `action == GatewayActions.CHAT` 传 list**；reply/create/close/abort 一律 null |

## 上下文（参考 commit）

- `0b6266a feat(skill-server): platformExtParam 落地三字段（businessSession{Domain,Type,Id}）` — 复用其组装路径，扩 builder 第 5 参
- `2b2075f fix(skill-server): personal 助手首次对话 retry chat payload 补全上下文字段` — 印证 first/retry 共享 `PendingChatRequest`，新字段同样需要写入

## Flowchart

```
chat 报文 → action 分流
  ├─ action != CHAT（QUESTION_REPLY/PERMISSION_REPLY/CREATE_SESSION/CLOSE_SESSION/ABORT_SESSION）
  │    → InvokeCommand.allowedSlashCommands = null
  │    → platformExtParam 不含该 key（哪怕在 personal scope）
  └─ action == CHAT → scope 分流
       ├─ scope != personal（business / default_assistant）
       │    → caller 仍可显式传 null；下游 strategy 用 4 参 builder 自然不下发
       └─ scope == personal CHAT
            ├─ normal（session + toolSessionId 就绪）
            │   → caller resolve → InvokeCommand(10 参) → GatewayRelayService 5 参 builder
            ├─ first（session 未建 / toolSessionId 缺）
            │   → caller resolve → PendingChatRequest.allowedSlashCommands 写入 pending list
            └─ retry（GW 回 session_created 回放 pending）
                → req.allowedSlashCommands() → InvokeCommand(10 参) + 5 参 builder（frozen 复用）
```

## 接入位置矩阵（personal CHAT 入口）

| # | 入口 | 文件:行 | 类型 | 改造点 |
|---|---|---|---|---|
| 1 | miniapp normal | `SkillMessageController.java:218-263`（routeToGateway） | CHAT / QUESTION_REPLY 双分支 | 构造 InvokeCommand 前判 `isChat`；仅 CHAT 走 resolver |
| 2 | miniapp first (legacy) | `SkillMessageController.java:210-215`（toolSessionId 缺 → `rebuildToolSession` 老 String 重载）| first | 不在 controller 改；改 `SessionRebuildService` legacy overload（见 #5/R5） |
| 3 | IM/External normal (case C) | `InboundProcessingService.java:339-407`（dispatchChatToGateway，含 :362 PendingChatRequest + :397 InvokeCommand） | CHAT | dispatchChatToGateway 顶端 resolve 一次，**同时**给 PendingChatRequest + InvokeCommand |
| 4 | IM/External first case A (personal) | `ImSessionManager.java:177-205`（createSessionAsync personal 分支构造 `PendingChatRequest`）| first | 构造 PendingChatRequest 前 resolve，传入 list |
| 5 | **IM/External first case B personal**（v2 新加） | `InboundProcessingService.java:230-239`（情况 B personal 分支调 `sessionManager.requestToolSession(session, prompt)` 老 String 重载）| first（legacy fallback） | **不在 controller 改**；老 String 重载链路最终走 `SessionRebuildService.rebuildToolSession(String, SkillSession, String, RebuildCallback)`（line 192-212）—— 由 R5 在该处统一 resolve |
| 6 | External chat | `ExternalInboundController.java:39-68`（委托 `InboundProcessingService.processChat`） | normal/first | 共用 #3/#4/#5，无单独改造 |
| 7 | retry 汇聚 | `GatewayMessageRouter.java:920-943`（retryPendingMessages）| retry CHAT | 从 `req.allowedSlashCommands()` 取 → InvokeCommand + 5 参 builder |
| 8 | personal builder 出口 | `GatewayRelayService.java:226`（buildInvokeMessage） | personal 出站汇聚 | 调 5 参 builder 时传 `command.allowedSlashCommands()` |

## 完整 callsite matrix（v2 收口 codex Critical 2）

> 通过 `grep "new InvokeCommand\("` / `grep "new PendingChatRequest\("` / `grep "PlatformExtParamBuilder\.build"` 验证。生产代码 callsite 100% 列出。

### A. `new InvokeCommand` — 12 处生产代码

| # | 文件:行 | action | scope（运行时） | allowedSlashCommands 传值 |
|---|---|---|---|---|
| A1 | `SkillSessionController.java:171` | CREATE_SESSION | personal | `null`（非 CHAT，决策 10） |
| A2 | `SkillSessionController.java:252` | CLOSE_SESSION | personal | `null` |
| A3 | `SkillSessionController.java:294` | ABORT_SESSION | personal | `null` |
| A4 | `SkillMessageController.java:259` | **CHAT or QUESTION_REPLY**（同方法双分支） | personal | **`isChat ? resolver.resolve(...) : null`** |
| A5 | `SkillMessageController.java:472` | PERMISSION_REPLY | personal | `null` |
| A6 | `SessionRebuildService.java:162` | CREATE_SESSION | personal | `null`（rebuild 触发 create_session） |
| A7 | `InboundProcessingService.java:397` | CHAT | personal/business | **`resolver.resolve(domain, type)`**（business scope 由下游 strategy 用 4 参 builder 忽略，无害） |
| A8 | `InboundProcessingService.java:465` | QUESTION_REPLY | personal/business | `null` |
| A9 | `InboundProcessingService.java:533` | PERMISSION_REPLY | personal/business | `null` |
| A10 | `GatewayMessageRouter.java:943` | CHAT | personal（retry 仅 personal） | **`req.allowedSlashCommands()`**（frozen 复用） |
| A11 | `ImSessionManager.java:163` | CHAT | **business**（business 即时发） | `null`（决策 7：仅 personal scope） |
| A12 | `ImSessionManager.java:318` | CREATE_SESSION | personal | `null` |

**总结**：12 处中 **3 处升 10 参 canonical 传 list**（A4 CHAT 分支 / A7 / A10），**9 处保持 9 参 secondary constructor**（默认 null）。

### B. `new PendingChatRequest` — 6 处生产代码

| # | 文件:行 | 触发场景 | allowedSlashCommands 传值 |
|---|---|---|---|
| B1 | `PendingChatRequest.java:136`（`fromSessionFallback` 内部）| record 静态工厂 | **加新 list 参数**（caller 显式传，不在 record 内 resolve） |
| B2 | `InboundProcessingService.java:362` | case C personal 写 pending（dispatchChatToGateway appendToPending=true） | **`resolver.resolve(...)`**（与 A7 共享一次 resolve） |
| B3 | `ImSessionManager.java:187` | case A personal 入 pending（first chat） | **`resolver.resolve(...)`** |
| B4 | `SessionRebuildService.java:205` | legacy String overload `fromSessionFallback` IAE 兜底（半填 entry） | `null`（已是 degraded path，保守传 null） |
| B5 | `SessionRebuildService.java:563` | rebuildFromStoredUserMessage plain text fallback | `null` |
| B6 | `SessionRebuildService.java:575` | rebuildFromStoredUserMessage 另一分支 plain text | `null` |

**总结**：B1 加新参数；B2/B3 显式 resolve；B4/B5/B6 传 null。

### C. `PlatformExtParamBuilder.build` — 4 处生产代码

| # | 文件:行 | scope | 改造 |
|---|---|---|---|
| C1 | `GatewayRelayService.java:226` | personal | **改 5 参重载**，传 `command.allowedSlashCommands()` |
| C2 | `GatewayMessageRouter.java:929` | personal retry | **改 5 参重载**，传 `req.allowedSlashCommands()` |
| C3 | `BusinessScopeStrategy.java:98` | business | 保持 4 参签名（不改） |
| C4 | `DefaultAssistantScopeStrategy.java:132` | default_assistant | 保持 4 参签名（不改） |

## Requirements

### R1 新建 `AllowedSlashCommandsResolver`（含严格 string[] 校验）

位置：`skill-server/src/main/java/com/opencode/cui/skill/service/AllowedSlashCommandsResolver.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AllowedSlashCommandsResolver {
    private static final String CONFIG_TYPE = "allowed_slash_commands";

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    /** 返 null（未配置 / parse 失败 / 非数组 / 含非文本元素 / 空数组 / 入参 blank）或非空 List<String>。 */
    @Nullable
    public List<String> resolve(@Nullable String domain, @Nullable String type) {
        if (domain == null || domain.isBlank() || type == null || type.isBlank()) return null;
        String key = domain + "_" + type;
        String json;
        try {
            json = sysConfigService.getValue(CONFIG_TYPE, key);
        } catch (RuntimeException e) {
            log.warn("[AllowedSlash] sysconfig read failed key={}: {}", key, e.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) return null;
        // v2 严格校验：readTree → isArray → 每个元素 isTextual
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                log.warn("[AllowedSlash] not a JSON array key={}: {}", key, json);
                return null;
            }
            List<String> list = new ArrayList<>(node.size());
            for (JsonNode el : node) {
                if (!el.isTextual()) {
                    log.warn("[AllowedSlash] non-textual element key={}: nodeType={}", key, el.getNodeType());
                    return null;  // 严格：含一个非文本元素就整体拒绝（不混合）
                }
                String s = el.asText();
                if (s != null && !s.isBlank()) list.add(s);
            }
            return list.isEmpty() ? null : list;
        } catch (JsonProcessingException e) {
            log.warn("[AllowedSlash] invalid JSON key={}: {}", key, e.getMessage());
            return null;
        }
    }
}
```

**行为契约**：
- 入参 blank → 直接 null（不查 sysconfig）
- sysconfig 异常 / null / blank value → null
- JSON 解析失败 / 非数组 → null + WARN
- 数组含**任一非 textual 元素**（数字 / 布尔 / 对象 / null）→ **整数组拒绝** + WARN（不做混合）
- 数组全是 blank 字符串 → null
- 复用 `SysConfigService` 内置 Redis 5min 缓存，**不再加一层**（对齐 `DefaultAssistantRuleService` 注释约定）
- 返 null 或非空 List 二态

### R2 扩 `PlatformExtParamBuilder` 加 5 参重载

位置：`skill-server/src/main/java/com/opencode/cui/skill/service/PlatformExtParamBuilder.java`

```java
// 保留原 4 参签名（business / default_assistant 继续用）
public static ObjectNode build(ObjectMapper om, String domain, String type, String sessionId) { ... }

// 新增 5 参重载（personal scope 用）
public static ObjectNode build(ObjectMapper om,
                               String domain, String type, String sessionId,
                               @Nullable List<String> allowedSlashCommands) {
    ObjectNode node = build(om, domain, type, sessionId);
    if (allowedSlashCommands != null) {
        ArrayNode arr = node.putArray("allowedSlashCommands");
        allowedSlashCommands.forEach(arr::add);
    }
    return node;
}
```

**契约**：list==null → 不出现 key；list 非空 → set JSON array；空数组分支不存在（R1 已归一为 null）。

### R3 `InvokeCommand` & `PendingChatRequest` record 改造（保留所有现有 secondary constructor）

> v3 收口（codex v2 Major 1 + Minor 1）：现有源码 + test 中 InvokeCommand 有 5/6/8/9 参 secondary constructor（62 处 test callsite），PendingChatRequest 有 8 参 canonical（~20 处 test callsite），`fromSessionFallback` 有 2 参签名（9 处 callsite）。**全部保留**，零改动现有 callsite；仅新增 canonical + 必要时新增一个 overload。

#### R3.1 `InvokeCommand`（10 参 canonical + 保留全部现有 secondary）

```java
public record InvokeCommand(
    String ak, String userId, String sessionId, String action, String payload,
    Boolean suppressReply,
    String businessSessionDomain, String businessSessionType, String businessSessionId,
    @Nullable List<String> allowedSlashCommands  // v3 新增
) {
    // —— 以下全部现有 secondary constructor 都要更新内部 this(...) 多传一个 null
    //    所有现有 callsite（5/6/8/9 参，含 62 处 test）零改动 ——

    /** 5 参 secondary：ak/userId/sessionId/action/payload，其余 null。 */
    public InvokeCommand(String ak, String userId, String sessionId, String action, String payload) {
        this(ak, userId, sessionId, action, payload, null, null, null, null, null);
    }
    /** 6 参 secondary：加 suppressReply。 */
    public InvokeCommand(String ak, String userId, String sessionId, String action, String payload,
                         Boolean suppressReply) {
        this(ak, userId, sessionId, action, payload, suppressReply, null, null, null, null);
    }
    /** 8 参 / 9 参 secondary 同理（具体签名以现网源码为准），内部 this(..., null) 传新字段。 */
    // ... trellis-implement 需把现有所有 secondary constructor 的 this(...) 调用末尾加一个 null ...
}
```

**改造范围**（参考 matrix 表 A）：**仅 A4 CHAT 分支 / A7 / A10 三处升 10 参 canonical 显式传 list**；其余 9 处生产代码 callsite + 62 处 test callsite **保持现有 5/6/8/9 参签名零改动**。

#### R3.2 `PendingChatRequest`（9 参 canonical + 保留 8 参 secondary + `fromSessionFallback` 2/3 参重载）

```java
public record PendingChatRequest(
    String text, String assistantAccount, String sendUserAccount,
    String imGroupId, String messageId, JsonNode businessExtParam,
    String businessSessionDomain, String businessSessionType,
    @Nullable List<String> allowedSlashCommands  // v3 新增
) {
    /** 8 参 secondary：v3 前的 canonical，向后兼容 ~20 个 test callsite + B4/B5/B6 null 兜底。 */
    public PendingChatRequest(String text, String assistantAccount, String sendUserAccount,
                              String imGroupId, String messageId, JsonNode businessExtParam,
                              String businessSessionDomain, String businessSessionType) {
        this(text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam,
             businessSessionDomain, businessSessionType, null);
    }

    /** 2 参 fromSessionFallback（向后兼容现网 9 处 callsite，默认 allowedSlashCommands=null）。 */
    public static PendingChatRequest fromSessionFallback(SkillSession session, String pendingMessage) {
        return fromSessionFallback(session, pendingMessage, null);
    }

    /** 3 参 fromSessionFallback（v3 新增，caller 显式传 list）。 */
    public static PendingChatRequest fromSessionFallback(SkillSession session, String pendingMessage,
                                                        @Nullable List<String> allowedSlashCommands) {
        // ... 原有 fromSessionFallback 逻辑，末尾 new PendingChatRequest 传 allowedSlashCommands ...
    }
}
```

**改造范围**（参考 matrix 表 B）：**仅 B2 / B3 升 9 参 canonical 显式传 resolved list**；B4/B5/B6 + 所有 test callsite 保持 8 参 secondary（默认 null）。`fromSessionFallback` 现网 9 处 callsite 中**仅 SessionRebuildService legacy String overload (R5) 升 3 参重载**，其余保持 2 参重载（默认 null）。

### R4 normal chat 接入（A4 / A7 / B2，**含 action guard + personal scope gating**）

> v3 收口（codex v2 Major 2）：resolver 调用必须双重门控（`action == CHAT` **且** `scope == personal`）。business / default_assistant scope 即使到 CHAT 分支也**不调** resolver——避免 business 热路径打 sysconfig + 污染日志。

#### A4 `SkillMessageController.routeToGateway`

利用 line 189 已经计算的 `scopeStrategy`：

```java
// ...action 计算保持不变（line 218-253）...

boolean isChat = GatewayActions.CHAT.equals(action);
// scope gating：personal scope 的 strategy.generateToolSessionId() 返 null（参考 InboundProcessingService.java:182,246）
boolean isPersonalScope = scopeStrategy.generateToolSessionId() == null;
List<String> allowedSlashCommands = (isChat && isPersonalScope)
    ? allowedSlashCommandsResolver.resolve(
        session.getBusinessSessionDomain(),
        session.getBusinessSessionType())
    : null;

gatewayRelayService.sendInvokeToGateway(
    new InvokeCommand(session.getAk(), effectiveUserId, sessionId, action, payload,
        null,
        session.getBusinessSessionDomain(),
        session.getBusinessSessionType(),
        session.getBusinessSessionId(),
        allowedSlashCommands));  // 10 参 canonical
```

> 注：`scopeStrategy` 在 line 189 是按 `assistantInfoService.getAssistantInfo(session.getAk())` 求出的。如果 `assistantIdProperties.isEnabled() == false`，line 189 的 strategy 仍然被算出来，所以 gating 总是工作。

#### A7 + B2 `InboundProcessingService.dispatchChatToGateway`

利用 `appendToPending` 作为 personal scope 的判定（line 246 `appendToPending = caseCStrategy.generateToolSessionId() == null` —— personal 时 true，business 时 false）。

resolver 仅在 personal scope 调用一次，复用给 InvokeCommand + PendingChatRequest：

```java
private InboundResult dispatchChatToGateway(...) {
    // ...
    // scope gating：appendToPending == true ≡ personal scope
    List<String> allowedSlashCommands = appendToPending
        ? allowedSlashCommandsResolver.resolve(businessDomain, sessionType)
        : null;

    if (appendToPending) {
        PendingChatRequest pendingRequest = new PendingChatRequest(
            prompt, assistantAccount, effectiveSender,
            "group".equals(sessionType) ? sessionId : null,
            messageId, businessExtParam,
            businessDomain, sessionType,
            allowedSlashCommands);  // B2: appendToPending=true ⇒ personal scope ⇒ list 可非空
        rebuildService.appendPendingMessage(String.valueOf(session.getId()), pendingRequest);
    }
    
    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
        ak, ownerWelinkId, String.valueOf(session.getId()),
        GatewayActions.CHAT,
        PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields),
        suppressReply,
        businessDomain, sessionType, sessionId,
        allowedSlashCommands));  // A7: business scope 时 null（双重保险：caller null + 下游 4 参 builder 也会忽略）
}
```

**business / default_assistant scope 不会读 sysconfig** —— resolver 完全不被 invoke，0 次 Redis 调用 + 0 条 WARN 日志。

### R5 first chat 接入（B1 / B3 / B4 + legacy String overload）

#### B3 `ImSessionManager.createSessionAsync` personal 分支

```java
List<String> allowedSlashCommands = allowedSlashCommandsResolver.resolve(businessDomain, sessionType);
pendingRequest = new PendingChatRequest(
    pendingMessage, assistantAccount, effectiveSender,
    "group".equals(sessionType) ? sessionId : null,
    String.valueOf(System.currentTimeMillis()),
    businessExtParam, businessDomain, sessionType,
    allowedSlashCommands);  // B3
```

#### legacy String overload 路径（miniapp first #2 + IM/External case B personal #5 共享）

**两条 caller path 都汇聚到** `SessionRebuildService.rebuildToolSession(String, SkillSession, String, RebuildCallback)`（line 192-212）。修改：

**1. `PendingChatRequest.fromSessionFallback` (B1) 加新参数**：

```java
// 旧：fromSessionFallback(SkillSession session, String pendingMessage)
// 新：fromSessionFallback(SkillSession session, String pendingMessage, @Nullable List<String> allowedSlashCommands)
```

不在 record 内部 resolve（避免 retry 路径 / DB rebuild 错误地拉当前 sysconfig，违反 freeze 语义）。

**2. `SessionRebuildService` 注入 `AllowedSlashCommandsResolver` + `AssistantInfoService` + `AssistantScopeDispatcher`，在 legacy String overload 内 personal scope gating 后再 resolve**：

> v3 收口：legacy String overload 不只被 miniapp first（personal）调用，还被 IM/External case B personal（`InboundProcessingService:237`）+ **business self-heal fallback (`InboundProcessingService:193`)** 调用——business self-heal 也会进入这条 overload。所以必须在 overload 内做 personal scope gating。

```java
@Deprecated
public void rebuildToolSession(String sessionId, SkillSession session,
        String pendingMessage, RebuildCallback callback) {
    PendingChatRequest pendingRequest = null;
    if (pendingMessage != null && !pendingMessage.isBlank()) {
        // scope gating：和 InboundProcessingService:246 同样判定，business self-heal 也会到这里
        AssistantInfo info = assistantInfoService.getAssistantInfo(session.getAk());
        boolean isPersonalScope = scopeDispatcher.getStrategy(info).generateToolSessionId() == null;
        List<String> allowedSlashCommands = isPersonalScope
            ? allowedSlashCommandsResolver.resolve(
                session.getBusinessSessionDomain(),
                session.getBusinessSessionType())
            : null;
        try {
            // B1 新 3 参签名
            pendingRequest = PendingChatRequest.fromSessionFallback(
                session, pendingMessage, allowedSlashCommands);
            log.warn("[WARN] rebuildToolSession(legacy String overload): ...");
        } catch (IllegalArgumentException iae) {
            log.error("[ERROR] rebuildToolSession(legacy String overload): ...");
            // B4 IAE 兜底：保守传 null（degraded path）
            pendingRequest = new PendingChatRequest(
                pendingMessage, null, null, null,
                String.valueOf(System.currentTimeMillis()), null,
                null, null,
                null);  // B4: allowedSlashCommands = null
        }
    }
    rebuildToolSession(sessionId, session, pendingRequest, callback);
}
```

> business self-heal fallback 进入此处时 `isPersonalScope == false` → list = null → fromSessionFallback 也传 null → 入 pending 的 entry `allowedSlashCommands = null`。如果 business self-heal 后续触发 retry（其实不会，因为 retry 只服务 personal scope，但理论上 pending list 仍会被消费），retry 下发 platformExtParam 不含该 key。**双重保险**。

**3. `rebuildFromStoredUserMessage` plain text fallback (B5 / B6)** 加 null 参数：

```java
return new PendingChatRequest(rawText, null, null, null, null, null, null, null, null);  // 9 个 null
```

这是 DB rebuild / plain text path，没有"新 chat 入 pending"语义，按 freeze 决策传 null（retry 时下发 platformExtParam 不含该 key）。

### R6 retry chat 接入（A10 / C2）

`GatewayMessageRouter.retryPendingMessages`（关键行 920-943）：

```java
// C2: line 929 改 5 参
extParameters.set("platformExtParam",
    PlatformExtParamBuilder.build(objectMapper,
        req.businessSessionDomain(),
        req.businessSessionType(),
        req.imGroupId(),
        req.allowedSlashCommands()));  // C2

// A10: line 943 升 10 参 canonical
sender.sendInvokeToGateway(new InvokeCommand(
    ak, userId, sessionId,
    GatewayActions.CHAT,
    payload,
    null,
    req.businessSessionDomain(),
    req.businessSessionType(),
    req.imGroupId(),
    req.allowedSlashCommands()));  // A10
```

### R7 personal scope builder 出口（C1）

`GatewayRelayService.buildInvokeMessage` 行 226：

```java
extParameters.set("platformExtParam",
    PlatformExtParamBuilder.build(objectMapper,
        command.domain(),
        command.domainType(),
        command.businessSessionId(),
        command.allowedSlashCommands()));  // C1
```

`sendInvokeToGateway` / `InvokeCommand` record accessor 自动 propagate，无须额外改字段。

### R8 GitNexus impact & detect_changes（项目 CLAUDE.md 强制）

trellis-implement 阶段：
- 修改 `PlatformExtParamBuilder` / `PendingChatRequest` / `InvokeCommand` / `SessionRebuildService` 前 MUST 跑 `gitnexus_impact({target: "X", direction: "upstream"})`
- commit 前 MUST 跑 `gitnexus_detect_changes({scope: "staged"})`

> Note: gitnexus 索引对 Java record constructor 的 `CALLS` edge 处理可能不完整（codex v1 + 本 PRD 验证时都遇到）。如 impact 返回偏少，用 `Grep` 直接搜 `new <Symbol>(` 作为双校验——本 PRD 的 callsite matrix 就是用此方法核出来的。

## Acceptance Criteria

| # | 场景 | 期望 |
|---|---|---|
| AC1 | sysconfig 有效配置 + personal **CHAT** 任意入口（A4 CHAT / A7 / A10） | `extParameters.platformExtParam.allowedSlashCommands` = 配置 list |
| AC2 | sysconfig 有效配置 + personal first → retry（中途改 sysconfig） | first 入 pending 取一次，retry 下发与 first 时刻一致（frozen） |
| AC3 | sysconfig 无配置 + personal CHAT 任意入口 | platformExtParam 不含该 key |
| AC4 | sysconfig 值非 JSON / 非数组 / parse 异常 | 同 AC3 + WARN 日志含 key + 异常 message |
| AC5 | sysconfig 值是空数组 `[]` / 仅 blank 元素 | 同 AC3（归一 null） |
| AC6 | `businessSessionDomain` 或 `businessSessionType` blank | 不查 sysconfig，不下发，不写 WARN |
| AC7 | business / default_assistant scope CHAT | platformExtParam 不含该 key（C3 / C4 走 4 参 builder） |
| AC8 | Redis pending list 含老 entry（无 allowedSlashCommands 字段）retry | Jackson 反序列化 = null → 不含该 key |
| **AC11** | **personal scope + action != CHAT**（A1/A2/A3 / A4 reply 分支 / A5 / A6 / A8 / A9 / A12 共 9 处）| **platformExtParam 不含该 key**（action guard 验证） |
| **AC12** | **sysconfig 值是数组但含非文本元素**（`[1,true]` / `[{}]` / `[null,"a"]`） | 同 AC3 + WARN 日志含 nodeType |
| **AC13** | **IM/External case B personal**（InboundProcessingService 情况 B personal 分支 → SessionRebuildService legacy String overload） | resolver 在 SessionRebuildService 内调用 → list 通过 `fromSessionFallback` 写入 pending → retry 下发 list |
| **AC14**（v3 新增）| **business / default_assistant scope CHAT 任意入口**（含 A7 / A11 / SessionRebuildService legacy overload 的 business self-heal 路径） | **resolver 完全不被 invoke**（0 次 sysconfig 查询 + 0 条 WARN 日志） + platformExtParam 不含 allowedSlashCommands key |
| AC9 | gitnexus_impact 对 4 个 shared symbol（builder / PendingChatRequest / InvokeCommand / SessionRebuildService） | 所有 d=1 caller 全部更新（含本 PRD callsite matrix 12+6 个生产代码点） |
| AC10 | gitnexus_detect_changes commit 前 | staged 变更范围 = builder + resolver + PendingChatRequest + InvokeCommand + SessionRebuildService + 3 处 caller (#1/#3/#4) + 测试 |

## Test Plan

### 单测

| 测试类 | 覆盖点 |
|---|---|
| `AllowedSlashCommandsResolverTest`（新增）| null / blank value / 正常 JSON / 非 JSON / 非数组 / 空数组 / 数组含 blank / **数组含数字 / 布尔 / 对象 / null 元素**（AC12）；blank domain/type（AC6）；sysconfig 抛 RuntimeException |
| `PlatformExtParamBuilderTest`（改造）| 5 参 list=null 不出现 key；list 非空出现 key；4 参签名行为不变（C3/C4 向后兼容） |
| `InvokeCommandTest`（改造）| 9 参 secondary constructor → allowedSlashCommands=null；10 参 canonical 各种值 |
| `PendingChatRequestSerializationTest`（新增）| **老 entry 反序列化字段=null（AC8）**；新 entry 序列化/反序列化往返一致 |
| `GatewayMessageRouterRetryTest`（改造）| retry 时 `req.allowedSlashCommands()` 传入 5 参 builder + 10 参 InvokeCommand |
| `SessionRebuildServiceTest`（改造）| legacy String overload 内 resolver 调用 + IAE 兜底传 null（B4） + plain text fallback 传 null（B5/B6） |
| `SkillMessageControllerTest`（改造）| **action guard：QUESTION_REPLY 分支 allowedSlashCommands=null（AC11）**；CHAT 分支调 resolver |

### 集测

- **normal 端到端**：mock sysconfig → 走 miniapp / IM / External normal → 校验 GW 收到 payload
- **first + retry 一致性**：mock sysconfig(list A) → first 入 pending → mock 改 list B → retry → 断言 retry payload 仍为 A
- **action guard 端到端**：同一 controller 跑 CHAT + QUESTION_REPLY + PERMISSION_REPLY → 断言只 CHAT 出现 key
- **case B personal 端到端**（AC13）：mock IM 入站消息 + session 已存在 + toolSessionId 缺 → 走 SessionRebuildService legacy String overload → 验证 pending entry 含 allowedSlashCommands

## Out of Scope

- 其他 scope（business / default_assistant）下发 allowedSlashCommands
- plugin 端如何消费（plugin 团队负责）
- sysconfig 配置项的人工录入流程
- `rebuild_legacy_string_overload` WARN 治理（已留 TODO，超出本任务）
- `SysConfigService.delete(Long id)` 改造为 type/key 感知（运营需求，见已知风险 5）

## 已知风险 & 假设

1. **InvokeCommand 升 10 参的兼容性**：v2 采用"9 参 secondary constructor + 10 参 canonical"方式，所有现有 callsite + test 零改动。仅 3 处 personal CHAT 显式升 10 参（A4 CHAT 分支 / A7 / A10）。
2. **legacy String 重载未迁移**：miniapp first（#2）/ IM case B personal（#5）走 `SessionRebuildService.rebuildToolSession(String, SkillSession, String, RebuildCallback)` 老 String 重载，PR3 已留 TODO 未迁移。v2 在该重载内 resolve，等老路径迁移到新 PendingChatRequest API 时需同步迁移 resolver 调用点（写入 `@Deprecated` 删除 checklist）。
3. **5min sysconfig 缓存 vs first/retry 一致性**：first 入 pending 时取的是 5min cache 内的值；如 `SysConfigService.update` 已主动 evict，新 chat 拿到的就是新值；如 update 还没被本进程感知，会拿旧 cache。**首次入 pending 之后**，retry 用 frozen 版本，**不再查 sysconfig**——所以 first/retry 一致性不受 cache 影响。
4. **plugin 兼容性假设**：plugin 端要能处理"`allowedSlashCommands` key 存在 / 不存在 / 是非空 array"三态。不在本 task 验证。
5. **`SysConfigService.delete(Long id)` 不知 type/key**（v2 新增）：`delete` 只接 id，无法定位 cache key，依赖 5min TTL 自然过期。**删除配置后旧缓存最多 5 分钟仍可能让 chat 拿到旧 list**。运营要立即生效请走 `update(status=0)` 路径（触发 evict）；若业务硬要求 delete 立即生效，需独立改造 `SysConfigService.delete`，超出本任务。
6. **business scope CHAT (A11 ImSessionManager:163) 不下发**：决策 7 明确"仅 personal scope"。caller 显式传 null + 下游 `BusinessScopeStrategy` 用 4 参 builder 不下发——**双重保险**。
7. **scope gating 依赖 `strategy.generateToolSessionId() == null` 判定 personal**（v3 新增）：这个判定方式来自 `InboundProcessingService.java:182,246` 等多处现网约定（personal scope strategy 返 null，business 预生成）。如果未来 strategy 实现重构（例如 personal 也预生成 toolSessionId），需要同步检查并升级 gating 逻辑——本 PRD 涉及 3 处 gating 点（`SkillMessageController.routeToGateway` / `InboundProcessingService.dispatchChatToGateway` 用 `appendToPending` 间接判定 / `SessionRebuildService` legacy overload）。建议 trellis-implement 抽 helper `AssistantScopeStrategy.isPersonal()` 统一封装。
