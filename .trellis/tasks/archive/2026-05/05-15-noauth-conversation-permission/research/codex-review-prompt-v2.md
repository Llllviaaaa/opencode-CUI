你是同一个资深 Java / Spring Boot 后端架构师。这是**第 2 轮评审**——上一轮你给出了 4 个 Critical + 5 个 Major + 5 个 Minor + 1 个替代方案建议（存档 `research/codex-review.md`）。开发方按你的反馈**整体重写了 PRD**，请验证修订是否真的闭合，并找新引入的问题。

## 上一轮 Critical 摘要（你提的）

- **C1** D4 businessTag 决策与真实调用链冲突（`BusinessScopeStrategy.buildInvoke` 读 `AssistantInfo.businessTag`，规则字段没有下游载体）。
- **C2** D6 "下游零改动"不成立——miniapp 通道 4 个 action（question_reply / permission_reply / close / abort）的 payload 缺 `assistantAccount` / `sendUserAccount`，business scope 触发即 IAE。
- **C3** D5 复用 SysConfigService pub-sub 是错误前提（该 service 没有 pub-sub channel）。
- **C4** D9 bind 漏掉 `updateToolSessionId` 的 Redis 映射 + route ownership 副作用。

## 这一轮新增的关键设计变化（请重点验证）

- **D2 扩字段**：规则 value JSON 从 3 字段扩到 **8 字段**（ak, assistantAccount, businessTag, cloudEndpoint, cloudProtocol, authType, cloudProfile, ownerWelinkId），把所有云端拨号元数据自带，不再依赖 `AssistantInfoService`。
- **D4 改方向**：**不再复用 business scope**——新建 `DefaultAssistantScopeStrategy implements AssistantScopeStrategy`，`getScope() = "default_assistant"`。完全自给自足：从规则取 endpoint/protocol/authType/businessTag/cloudProfile，直接选 CloudRequestProfile 构造云端请求。**不进 BusinessWhitelistService.allowsCloud(...) gate**。
- **D5 自管缓存**：放弃 SysConfigService pub-sub 复用——`DefaultAssistantRuleService` 自己持 `AtomicReference<ImmutableRuleSnapshot>` 原子快照，`TaskScheduler` 周期 60s 兜底 reload，新增 admin `POST /api/admin/default-assistant-rules/reload` 即时触发；reload 失败保留旧 snapshot。
- **D10 反查识别**：**不加 SkillSession schema 列**（user 明确要求）——`DefaultAssistantRuleService` 暴露 `findByAk(ak)` / `findByAssistantAccount(account)`；`AssistantScopeDispatcher.getStrategy(String ak, AssistantInfo info)` 先反查 virtual，命中即返 `DefaultAssistantScopeStrategy`。4 处 caller 都改到新 API。
- **D11 修 C2**：default-assistant PR 内顺便把 4 处 payload 都补齐 `assistantAccount` + `sendUserAccount`。
- **D12 deletion check 短路**：3 处 `assistantAccountResolverService.check(account)` 之前先判 `ruleService.findByAssistantAccount(account).isPresent()` → 命中即 skip。
- **D8 修订**：强调 `request.businessSessionDomain` 必须在 **controller 入口、原始 request 字段**上判定，避免被 `SkillSessionService` 默认归一化为 `"miniapp"`（你之前提的 M2）。
- **加载校验升级**：M3 修订——`assistantAccountResolverService.check == EXISTS` 才放行（不再容忍 UNKNOWN）；cloudProtocol / authType 枚举校验；`domain`/`domainType` 不含分隔符 `` 校验。
- **拆 4 个 PR**：PR1 RuleService 独立；PR2 ScopeStrategy + Dispatcher API + 4 处 caller；PR3 Controller 注入 + bind + payload + deletion 短路；PR4 集成测试 + 文档。

## 修订 PRD 路径

`.trellis/tasks/05-15-noauth-conversation-permission/prd.md`

## 待 ground-truth 的代码（必读，别只看 PRD）

- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionAccessControlService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/AssistantSquareCloudRequestStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestContext.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/profile/CloudRequestProfileRegistry.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/BusinessWhitelistService.java`
- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml`
- `skill-server/src/main/resources/mapper/SysConfigMapper.xml`（如果存在）
- `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md`

## 评审任务

### 1. 验证上一轮 4 个 Critical 是否真正闭合

| 上一轮 Critical | 修订点 | 你的验证 |
|---|---|---|
| C1 businessTag 路径冲突 | D2 扩字段 + D4 独立 strategy 完全旁路 BusinessScopeStrategy | 真的不再走 `info.getBusinessTag()` 吗？`AssistantScopeDispatcher` 是否会在某条边路上仍 hit business gate？ |
| C2 4 处 payload 缺字段 | D11 在同一 PR 里修 4 处 | 4 处具体位置（line 213-221, 429-434, 197-204, 231-238）的 payload 是否被 Requirement 列全？修完之后 personal scope 那一路（`buildInvokeMessage`）会不会因为多了这俩字段而出问题？ |
| C3 SysConfigService pub-sub | D5 改为自管 AtomicReference + 周期 + admin reload | 真的不再依赖任何不存在的机制吗？`ImmutableRuleSnapshot` 原子替换是否真的能避免半载入窗口？周期 reload 与 admin reload 并发会不会有竞态？ |
| C4 bind 漏 Redis/route 副作用 | D9 / Requirement 9 改为复用 `updateToolSessionId` 完整逻辑 | `SkillSessionService.updateToolSessionId` 实际行为是否真的包含 Redis 映射 + route ownership？bindDefaultAssistant 用它一招到位对吗？还有什么副作用没被复用？ |

### 2. 验证上一轮 Major 是否处理

- **M1**（tripwire 静默无 AI）：D5 已把"reject 摘要"放进日志和 metric。是否够？需不需要 readiness gate？
- **M2**（businessSessionDomain 默认值绕开 D8）：D8 已说"在 controller 入口、原始 request 字段判定"。代码上怎么实现才稳？是否需要在 SkillSessionService 也加一道守卫？
- **M3**（UNKNOWN 放行）：Requirement 2 第 4 项已升级到 EXISTS。OK 吗？
- **M4**（reload 可见性 + startup race）：D5 已说"原子替换 + 失败保留旧"。HTTP 端口与 ApplicationReadyEvent 的时序是否还有隐患？
- **M5**（D6/D9 语义需澄清）：是否在文档/SOP 层面已经处理清楚？

### 3. 新引入的设计点找新问题

- **D10 反查 ak**：`AssistantScopeDispatcher.getStrategy(String ak, AssistantInfo info)` 改 API，要改 4 处 caller。有没有遗漏的 caller？`@Deprecated` 旧方法委托新方法的写法会不会有递归 / 死循环风险？
- **DefaultAssistantScopeStrategy.buildInvoke**：跟现有 `BusinessScopeStrategy.buildInvoke` 重叠的逻辑（CloudRequestContext 字段映射、QUESTION_REPLY/PERMISSION_REPLY 分支、parseAnswers）是否会出现长期维护漂移？要不要抽公共基类？
- **admin reload endpoint**：用 `ImTokenAuthInterceptor` 现有 token 鉴权够吗？这个 token 当前与 IM Inbound 共用，权限边界是否合适？
- **deletion check 短路**：3 处短路代码 + 注入逻辑，会不会出现"先注入 → 再 deletion check → 短路放行 → 但 deletion check 本来想拦的其他状态没被照顾到"？比如 EXISTS 但被 ban 的情况？
- **拆 PR 顺序**：PR1 RuleService 独立、但 PR1 完成时 ScopeStrategy 还没接入——是否会在 PR1 单独上线时出问题？还是 PR1 仅合并不发布？

### 4. AC 完备性

5 块 AC（A/B/C/D/E）有没有遗漏的失败场景？特别检查：

- C 区"老路径不回归"是否真覆盖了 personal scope 4 个 action 加了新字段后的兼容性（PCAgent 那边是否会因为 payload 多字段拒绝）？
- E 区独立 scope 路由验证够不够直接？

### 5. 拆 PR 是否合理 / 可并行

PR1 / PR2 / PR3 / PR4 之间的依赖与并行可能。CI / 部署时的兼容窗口（中间状态）。

## 输出格式

```markdown
# Codex 评审 (第 2 轮) — noauth-conversation-permission PRD

## 摘要（不超过 5 行）
[整体判断 + 是否可以进入实施]

## Critical Issues（修订后仍存在 / 新引入）
（如有）

## Major Concerns
（如有）

## Minor 建议
（如有）

## 上一轮 Critical/Major 关闭情况确认
- C1 → [已闭合 / 部分闭合 / 未闭合]：理由
- C2 → ...
- ...
- M1 → ...
- ...

## 总评结论
- 是否可以进入实施？
- 若不可以，列出"先做哪几件事再开 PR1"。
```

中文输出。所有判断必须**指向具体文件 + 行号**或具体决策号 (D1-D12) 或 Requirement 编号。
