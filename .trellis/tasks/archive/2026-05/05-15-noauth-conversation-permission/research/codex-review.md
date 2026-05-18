# Codex 评审 — noauth-conversation-permission PRD

## 摘要（不超过 5 行）
整体判断：**不能直接进入实施**，风险等级 **High**。  
PRD 的主方向可行，但几个核心承诺和当前代码不一致：businessTag 不可能“只用规则表”、business scope 的非 chat 动作会缺字段炸、sys_config pub-sub 假设不成立、D9 补绑会漏 Redis 映射/并发守卫。  
这些不是实现细节，是方案边界没闭合。

## Critical Issues（必须改才能开工）

### C1: D4 的 businessTag 决策与真实调用链冲突
- **根因**：PRD 说 businessTag 写死在规则表，`CloudRequestProfileRegistry.resolve(businessTag)` 走规则值（`.trellis/tasks/.../prd.md:98-107`）。但真实代码里 `BusinessScopeStrategy.buildInvoke()` 只从 `AssistantInfo` 取 businessTag：`info.getBusinessTag()`（`BusinessScopeStrategy.java:65-66`），随后用它解析 profile（`BusinessScopeStrategy.java:114`）。规则里的 businessTag 没有任何下游载体。
- **额外坑**：`AssistantScopeDispatcher` 对 business scope 还有白名单 gate，`businessTag` 不在白名单会降级到 personal（`AssistantScopeDispatcher.java:61-68`），这直接破坏 D4 “默认助手限定 business scope”的保证。
- **建议**：二选一：  
  1. 删除规则表 businessTag 字段，明确依赖 `AssistantInfo.businessTag`，tripwire 改成告警而非丢弃。  
  2. 真要规则 businessTag 生效，就必须把它持久化到 session 或传入 `InvokeCommand`/payload，并让 `BusinessScopeStrategy` 使用规则值。
- **影响 AC**：A. businessTag 校验；B. chat/permission 端到端；PR1/PR2 的规则模型与下游零改动承诺。

### C2: D6 “下游零改动”不成立，business 非 chat 动作会缺 assistantAccount / sendUserAccount
- **根因**：chat 分支会放入 `assistantAccount` 与 `sendUserAccount`（`SkillMessageController.java:224-230`），但 `question_reply` 分支只放 `answer/toolCallId/toolSessionId/businessExtParam`（`SkillMessageController.java:216-221`），`permission_reply` 也只放 `permissionId/response/toolSessionId/businessExtParam`（`SkillMessageController.java:429-434`）。`close_session/abort_session` payload 也只有 `toolSessionId`（`SkillSessionController.java:197-204`, `231-238`）。
- **为什么会炸**：business strategy 统一从 payload 抽 `assistantAccount` / `sendUserAccount`（`BusinessScopeStrategy.java:98-103`），`AssistantSquareCloudRequestStrategy.build()` 入口直接校验空值并抛 `IllegalArgumentException`（`AssistantSquareCloudRequestStrategy.java:48-49`, `88-99`）。`GatewayRelayService` 调 `strategy.buildInvoke()` 后没有捕获这个异常（`GatewayRelayService.java:108-115`）。
- **建议**：所有 business action 的 payload 都必须带齐 `assistantAccount`、`sendUserAccount`，或把 `SkillSession` 注入到 build context，不能只改 createSession。
- **影响 AC**：B 中 `question_reply`、`permissions`、`abort`、`delete` 的端到端验收。

### C3: D5 复用 SysConfigService 的 pub-sub invalidate 是错误前提
- **根因**：当前 `SysConfigService` 只有单 key Redis cache + DB fallback：读 Redis，miss 查 DB，status=1 才写缓存（`SysConfigService.java:48-83`）。create/update 只 `redisTemplate.delete(cacheKey)`（`SysConfigService.java:101-117`, `137-145`），delete 甚至不删缓存，只等 TTL（`SysConfigService.java:120-128`）。`listByType()` 直接查 DB（`SysConfigService.java:92-93`）。没有 sys_config pub-sub channel。
- **后果**：PRD 的“60s 内含 Redis pub-sub 生效”（`prd.md:212`）和“复用 SysConfigService 现有机制”（`prd.md:201`）没有代码基础。运营直接 SQL `UPDATE sys_config` 时，应用也不会收到任何事件。
- **建议**：PR1 先补一个明确的规则刷新机制：`AtomicReference<Map<CompositeKey, Rule>>` 原子替换、版本号/generation、周期兜底 reload、管理 API 发布刷新事件。不要写“复用现有 pub-sub”，除非先实现它。
- **影响 AC**：A 的缓存刷新、回滚、并发 reload；PR1 的核心服务设计。

### C4: D9 补绑的写路径漏掉现有创建路径的副作用
- **根因**：显式 ak 创建 session 时，`SkillSessionService.createSession()` 不只写 `skill_session`，还会创建 session ownership route（`SkillSessionService.java:71-75`）。toolSessionId 更新也不只是 SQL，会写 Redis `toolSessionId -> sessionId` 映射并清理旧映射（`SkillSessionService.java:228-240`）。
- **PRD 漏项**：D9/PR2 只写“UPDATE 三个字段”（`prd.md:199`, `324`），没有说明 Redis toolSession mapping、route ownership、旧 toolSessionId 处理，也没有要求 SQL 同时守卫 `ak IS NULL` 和非 CLOSED。
- **建议**：`bindDefaultAssistant` 必须是事务语义：`WHERE id=? AND ak IS NULL AND status <> 'CLOSED'`，成功后复用 `updateToolSessionId()` 的映射逻辑，必要时 `createRoute(...)`，最后重读 session 返回。更新行数为 0 必须转 409，不能覆盖。
- **影响 AC**：D 全部，尤其重复 bind、TOCTOU、补绑后立刻发消息。

## Major Concerns（建议改）

### M1: D2 + D4 的 tripwire 失败模式会制造“静默无 AI”
- **根因**：businessTag mismatch 会把规则丢弃（`prd.md:187-191`, `206-212`），而未命中规则会回到空壳 session（`prd.md:90`, `149`）。调用方只能看到创建成功，后续 `routeToGateway` 因 ak null 跳过（`SkillMessageController.java:170-175`）。
- **建议**：规则被拒绝不能只打 ERROR 日志。需要健康检查/指标/启动摘要，并定义是否阻断 readiness。否则运营改错一行配置，用户侧表现就是“没回复”。

### M2: D8 + D9 与当前 domain 默认值冲突
- **根因**：D8 说 domain/type 任一为空就跳过规则（`prd.md:146-149`）。但落库时 `businessSessionDomain` 为空会被改成 `miniapp`（`SkillSessionService.java:61-64`），type 原样保存（`SkillSessionService.java:65`）。所以“创建时 domain 为空、type 非空”的 session，后续 D9 可能按 `(miniapp, type)` 补绑，而不是永远无路可走。
- **建议**：补绑判断必须基于创建请求原始语义，或明确禁止这种补绑。更简单：D9 只允许 domain/type 都是用户显式传入且非空的历史 session。

### M3: 规则校验允许 UNKNOWN，默认助手身份不够硬
- **根因**：`AssistantAccountResolverService.check()` 对 blank、resolveUrl 缺失、远端异常都可能返回 UNKNOWN（`AssistantAccountResolverService.java:127-129`, `166-168`）。PRD 只要求 `check(assistantAccount) != NOT_EXISTS`（`prd.md:191`），等于 UNKNOWN 也能入规则。
- **建议**：默认助手规则应要求 `EXISTS`，远端不可用时规则进入 “unverified” 不生效，或者阻断 readiness。默认助手是控制面配置，不应按普通请求的 fail-open 策略处理。

### M4: reload 可见性和 startup race 没定义
- **根因**：PRD 只说 `ConcurrentHashMap` + invalidate（`prd.md:201`, `308-315`），没规定是 clear/put 还是构建新 map 后原子替换。`ApplicationReadyEvent` 加载还可能晚于 HTTP 端口可接流量（`prd.md:308`）。
- **建议**：用 `AtomicReference` 原子快照；初始化在 bean ready 前完成，或加 readiness barrier。reload 失败保留上一代规则，不要清空后半载入。

### M5: D6 与 D9 的用户语义需要重新写清楚
- **根因**：D6 说规则改动不影响老 session（`prd.md:137-138`），D9 又允许老 session 使用“当前规则”补绑（`prd.md:154-158`）。这不是矛盾，但会让排障变复杂：同一个老 session 是否改变，取决于有没有人手动调 bind。
- **建议**：统一表述为：“规则自动生效仅限新 session；老 session 只能通过显式 bind 改变，bind 使用调用时刻的当前规则，并写审计日志。”

## Minor / 风格建议

1. `SkillSession.Status` 只有 `ACTIVE/IDLE/CLOSED`（`SkillSession.java:85-91`），不要在 AC 里暗示存在 ABORTED 状态；当前 abort 不改 DB 状态（`SkillSessionController.java:225-242`）。
2. `config_key='{domain}:{domainType}'`（`prd.md:115-118`）要定义冒号转义或禁止 domain/type 含 `:`，否则 composite key 有碰撞风险。
3. `trim` 与大小写敏感要落测试。PRD 计划里提到 trim、大小写敏感（`prd.md:312-313`），但 AC 没列。
4. createSession 当前更新 toolSessionId 后返回的还是旧 session 对象（`SkillSessionController.java:117-123`, `137-139`）；默认助手注入后最好返回重读后的 session，避免响应字段和 DB 不一致。
5. PRD 后半仍保留“待确认/Q1”旧段落（`prd.md:278-296`），开工前删掉，避免实现者误读范围。

## 替代方案 / 改良建议

更稳的方案是新建 `default_assistant_rule` 表，而不是多行 `sys_config` JSON：字段用 `domain`, `domain_type`, `ak`, `assistant_account`, `business_tag/cloud_profile`, `status`, `version`, `updated_at`，加唯一索引 `(domain, domain_type)`。这样可以做 DB 约束、版本化 reload、审计和状态回滚。

如果坚持 sys_config MVP，也要把它当“存储载体”，不要假装已有 pub-sub：新增专用 `DefaultAssistantRuleService.reload()`、原子快照、周期兜底、拒绝规则指标、管理 API 触发刷新。

D9 不建议一开始做 `force` 给普通 cookie 用户。若要 force，只能是 admin/ops 接口，必须要求 expectedOldAk / reason / audit log，避免把人工绑定误覆盖。

## 总评结论

- **是否可以进入实施？** 不可以。PRD 需要先修正 Critical Issues。
- **开 PR1 前先做这几件事**：  
  1. 重新定 D4：businessTag 到底来自规则还是 AssistantInfo，并改调用链设计。  
  2. 补齐 business action payload/context，覆盖 chat、question_reply、permission_reply、close、abort。  
  3. 重写 D5 缓存刷新方案，不再依赖不存在的 SysConfigService pub-sub。  
  4. 重写 D9 bind 的原子更新、Redis toolSession 映射、route ownership、旧 toolSessionId 策略。