# Codex 评审 (第 3 轮) — noauth-conversation-permission PRD

## 摘要（≤ 5 行）
当前源码仍基本是旧实现，PRD 修订点尚未落地，不能说上一轮 Critical 已在代码中关闭。  
PR1（GW fallback by cloudProfile）可以启动，但 PR2/PR3 前还有两个设计级 blocker：`GatewayRelayService` 无法识别 default assistant strategy，以及规则命中/显式 ak 的优先级表述不一致。  
本轮最大的新增风险不在 payload 字段，而在 dispatcher/API 接入点和 admin token 边界。

## 上一轮 Critical 关闭情况
- C1 → **未闭合**：当前仍是 `GatewayRelayService` 取 `AssistantInfo` 后调 `scopeDispatcher.getStrategy(info)`，再只对 `"business"` 走 `strategy.buildInvoke`（`skill-server/.../GatewayRelayService.java:107-118`）。`BusinessScopeStrategy.buildInvoke` 仍读 `info.getBusinessTag()`（`BusinessScopeStrategy.java:65-66`），dispatcher 仍走白名单 `allowsCloud(info.getBusinessTag())`（`AssistantScopeDispatcher.java:57-69`）。源码无 `DefaultAssistantScopeStrategy` / `DefaultAssistantRule` 命中路径。
- C2 → **未闭合**：chat 已带 `assistantAccount/sendUserAccount`（`SkillMessageController.java:224-233`），但 `question_reply` 只放 `answer/toolCallId/toolSessionId/businessExtParam`（`SkillMessageController.java:216-221`），`replyPermission` 只放 `permissionId/response/toolSessionId/businessExtParam`（`SkillMessageController.java:429-434`）。close/abort 仍发 GW 且只带 `toolSessionId`（`SkillSessionController.java:197-204`, `231-238`），而 GW `ACTION_TO_SCOPE` 不含 close/abort（`CloudAgentService.java:41-46`）。不过 personal PCAgent 端 normalizer 会忽略未知字段而非拒绝：`DownstreamMessageNormalizer.ts:236-265`, `284-328`。
- C3 → **未闭合**：现有 `SysConfigService` 是 Redis/DB getValue 缓存（`SysConfigService.java:48-84`），没有 `AtomicReference` 规则快照、60s reload、reload 摘要；`SysConfigController` 只有 configs CRUD/value（`SysConfigController.java:39-97`）。PRD Req 2 / AC 已写 reload 失败保留旧 snapshot（`prd.md:183-195`, `257-260`），但源码无实现。
- C4 → **决策闭合，功能未修**：PRD 明确 OOS 不迁移历史 `ak=null` session / 不加补绑接口（`prd.md:348-349`）。这意味着老空壳 session 仍是死会话：`routeToGateway` 遇 `session.ak == null` 直接跳过 GW（`SkillMessageController.java:172-175`）。若产品接受“历史空壳不救”，撤回合适；否则仍需补绑/迁移方案。

## 本轮新变化的风险评估
- 改变 1：GW `SysConfigFallbackProviderV2` — **Medium**。当前 `CloudAgentService` 已取 `cloudProfile`（`CloudAgentService.java:77-81`），但调用仍是 `getConfig(ak, scope)`（`CloudAgentService.java:96`）；`CallbackConfigService` cache key 也只有 `{version}:{ak}:{scope}`（`CallbackConfigService.java:87-100`）；老 fallback 只按 shortName 缓存（`SysConfigFallbackProvider.java:33-40`, `63-80`）。加 cloudProfile 维度会让老 ak 至少冷 miss 一次，属可接受风险；建议保留 `getConfig(ak, scope)` overload 委托 `"default"` 降低测试/调用方冲击。
- 改变 2：default close/abort 跳过 GW — **Medium**。对 default assistant 是正确的，因为 GW 会 unknown action（`CloudAgentService.java:41-46`）。但 PRD 说“未来真业务助手 miniapp latent bug”也被修，这只有在 close/abort 按 strategy 能力统一判断时才成立；如果仅 `ruleService.lookup(domain,type)` 命中才跳过，显式真业务 ak 仍会发 GW。DB `CLOSED` 足以让后续 sendMessage 409（`SkillMessageController.java:134-136`），但 abort 语义会从“可复用”变成关闭，需要 AC 明确。
- 改变 3：未命中规则 + 无 ak/assistantAccount → 400 — **High**。当前代码仍由 `isSkipOnNullAssistantAccount` 控制（`SkillSessionController.java:83-90`），且默认值是 true（`AssistantAccountResolverService.java:63-66`）。PRD 内部也冲突：D1 说未命中且无显式字段返 400（`prd.md:50-56`），AC C 又说 domain/type 任一空时继续走旧开关（`prd.md:296-298`）。需要先给出完整优先级矩阵。
- 改变 4：admin reload 用 `skill.admin.token` — **High**。源码没有 `skill.admin.token` 配置/拦截器；`WebMvcConfig` 只保护 `/api/inbound/**`、`/api/external/**`（`WebMvcConfig.java:36-37`），IM token 拦截器只读 `skill.im.inbound-token`（`ImTokenAuthInterceptor.java:29-33`）。同时 GW 的 `SkillServerConfigClient` 会给 `/api/admin/configs/value` 带 `gateway.skill-server.api-token`（`SkillServerConfigClient.java:54-63`）；若 PR2 直接拦截 `/api/admin/**`，必须同步放行/适配 GW 配置读取。

## 新引入的盲点（如有）
### N1：default strategy 在 GatewayRelayService 处会丢失
症状：controller 即使命中规则并注入 virtual ak，`GatewayRelayService.sendInvokeToGateway` 仍只拿 `command.ak()` 去查上游 `AssistantInfo`（`GatewayRelayService.java:107-118`）。virtual ak 上游不存在时 info=null，旧 API 会落 personal，不会进 default strategy。  
根因：`InvokeCommand` 没有 domain/type，`GatewayRelayService` 也没有按 ak 先 `findByAk` 的策略选择点。  
建议：在 `GatewayRelayService` 内部先 `ruleService.findByAk(command.ak())`，命中直接选 `default_assistant`；不要依赖 controller 之前查过规则。影响 AC §B chat/question/permission。

### N2：规则命中与显式 ak/assistantAccount 的优先级冲突
症状：D1 写“命中规则即默认助手”（`prd.md:52-56`），AC 又要求“显式传 ak/assistantAccount 且 domain/type 命中规则时不走规则注入”（`prd.md:296-297`）。  
建议：PR1 前把优先级写死：显式 ak 或 assistantAccount 是否总是 override 规则；domain/type 为空是否保留旧 skip 开关。否则 PR3 测试会互相打架。

### N3：AtomicReference 不等于 reload 并发安全
症状：PRD 要 admin reload + 60s 周期 reload（`prd.md:183-199`），但只写 AtomicReference。  
根因：AtomicReference 只能保证替换原子性，不能防止“较早开始、较晚结束”的旧 reload 覆盖新 reload。  
建议：reload 串行化（single-thread scheduler + admin 同一锁）或 CAS version；失败时不 swap。影响 AC §A reload 失败/并发 reload。

### N4：createSession 的事务和响应需要收口
症状：当前 `createSession` 落库在一个事务（`SkillSessionService.java:49-80`），随后 controller 再 `updateToolSessionId`（`SkillSessionController.java:104-120`，`SkillSessionService.java:227-240`）。  
建议：default assistant 最好用 service 层一个事务完成“创建 session + toolSessionId + Redis mapping”，并返回重读后的 session。Redis mapping 不应跳过；上行事件会通过 `toolSessionId` 反查 session（`GatewayMessageRouter.java:1047-1070`）。

### N5：deletion check 简化方案基本可行，但 createSession 必须先判规则
证据：当前 createSession 删除校验在落库前直接查 `assistantAccountResolverService.check`（`SkillSessionController.java:82-102`）；send/reply 也是先 deletion check 再 route（`SkillMessageController.java:138-143`, `401-406`）。  
建议：send/reply 用 `lookup(session.domain,type)` 短路可行；createSession 必须在 line 82 前完成“显式字段 override / 规则命中注入 / 未命中 400”判定。

### N6：两套 profile 配置需要 SOP 强约束
PRD 已识别命名分裂（`prd.md:359`, `373`）。SOP 建议固定写成一张映射表：`default_assistant_rule.businessTag` → SS `cloud_protocol_profile:{businessTag}` → `profileName` → GW `cloud_route_fallback_v2:{profileName}:{chat|question|permission}`。最好约定 `profileName == businessTag`，否则排障成本会明显上升。

## 总评结论
- **可以进入 PR1**，但只限 GW 侧 fallback by cloudProfile；PR1 合并前必须完成 `getConfig(ak, scope, cloudProfile)`、cache key 维度、新旧 provider 分派、CloudAgentService 调用点和单测。
- **不能直接进入 PR3**。先修 N1、N2、N3，再实现 D7/D8/Requirement 11。
- 当前源码不能宣称 4 个 Critical 已关闭；它们大多只是写进了 PRD。