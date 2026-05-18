你是同一位资深 Java / Spring Boot 后端架构师。这是**第 3 轮评审**。

## 背景：前两轮发生了什么

- **Round 1**（`research/codex-review.md`）：你给出 4 个 Critical + 5 个 Major + 5 个 Minor + 1 个替代方案建议。
- **Round 2**：codex 额度耗尽，没跑成。开发方做了手工 self-review，发现自己引入了 C-new-1（误把 v1 的"M3 改为 EXISTS 校验"抄进 v2，但 v2 已经决定 virtual ak 上游不存在——直接冲突）。

期间开发方与产品方做了多轮深入对话，发现并修正了几处之前你没点到的设计盲点：
1. **GW 也必须改**——之前以为"GW 不用动"是错的。`SysConfigFallbackProvider` 当前**只按 scope 区分**（chat/question/permission），**不按 ak / cloudProfile 区分**。
   多个 virtual ak 的不同云端 endpoint 无法配置；改动落在新增 `SysConfigFallbackProviderV2` 按 (cloudProfile, scope) 查 `cloud_route_fallback_v2` 这个新 config type。
2. **`ownerWelinkId`** 之前误塞进规则表——实际它只在 external/IM 入站（没有 cookie userId）场景用作 sendUserAccount fallback。
   miniapp 通道有 cookie userId，根本不需要 ownerWelinkId。已删字段，规则表瘦回 3 字段。
3. **`cloudEndpoint` / `cloudProtocol` / `authType` / `cloudProfile`** 之前塞进规则表——错。
   commit b2e8940 已经确立"跨服务 contract = profile name 字符串"，这些字段归 GW 的 `cloud_protocol_profile` SysConfig 管。规则表只需要 businessTag 一个。
4. **close_session / abort_session** 在 business 类下根本不该发 GW——`CloudAgentService.ACTION_TO_SCOPE` 不含这俩 action，发过去会回 `Unknown action` TOOL_ERROR。
   实际是 latent bug，本任务顺手修。命中 default_assistant 规则的 close/abort 改为"仅 DB 标 status=CLOSED，不发 GW"。
5. **未命中规则的 createSession 行为**：从"老 isSkipOnNullAssistantAccount 开关放行空壳"改为"直接 400 `ak 和 assistantAccount 必填`"——避免静默创建无 AI 的空壳。
6. **admin reload endpoint 鉴权**：从"复用 IM Inbound token"改为"独立 `skill.admin.token`"，权限边界清晰。
7. 撤回 D9 补绑接口（OOS，下一期）。
8. `DefaultAssistantScopeStrategy.buildInvoke` 内部用 `findByAk(ak)` 反查规则；controller 入口用 `lookup(domain, type)`——两类反查方法。

## 你这一轮的任务

请用 `read-only` 沙箱**实读源码**（不要光看 PRD）验证以下三组问题：

### 1. 上一轮 4 个 Critical 当前关闭情况

| 项 | 修订点 | 你的验证 |
|---|---|---|
| C1 businessTag 路径冲突 | D4 独立 strategy，`DefaultAssistantScopeStrategy.buildInvoke` 内部从 rule 取 businessTag，旁路 BusinessScopeStrategy + 白名单 gate | 真的旁路了吗？有没有别的代码路径还会读 `info.businessTag`？ |
| C2 4 处 payload 缺字段 | D8 实际只补 question_reply / replyPermission 两处，close/abort 因 D7 不发 GW 无需补 | 这种"只修 2 处、close/abort 用 D7 跳开"的策略是否真的覆盖完整？personal scope 路径加这俩字段会不会被 PCAgent 拒绝？ |
| C3 SysConfigService pub-sub | D5 + Requirement 2 改为 `AtomicReference` 原子快照 + `TaskScheduler` 周期 60s reload + admin reload endpoint（独立 token） | reload 失败保留旧快照、admin reload 与周期 reload 并发是否安全？ |
| C4 bind 漏 Redis/route 副作用 | D9 撤回（OOS） | 撤回是否合适？没有补绑接口意味着老 ak=null session 永远是死的——这是否符合产品预期？ |

### 2. 本轮新引入的 4 个变化点的风险

| 变化点 | 你的验证 |
|---|---|
| GW 侧新 `SysConfigFallbackProviderV2`（按 cloudProfile + scope 区分） | `CallbackConfigService.getConfig` 签名加 cloudProfile 是否会影响真业务助手 / 老调用方？cloudProfile == "default" 走老 provider 这个分叉点是否稳？缓存 key 加维度会不会让老 ak 的 cache 失效一次（cold start 多查一次上游）？ |
| close/abort 命中规则跳过发 GW | 真业务助手未来接入 miniapp 时这条改动会不会破坏什么？close/abort 跳过发 GW 后，DB 标 status=CLOSED 是否足以让后续 sendMessage 收 409？ |
| 未命中规则 + 没显式 ak/assistantAccount → 400 | 现有 `isSkipOnNullAssistantAccount` 开关被这条规则覆盖。是否会让某些老调用方（依赖空壳创建）回归？ |
| admin reload endpoint 用 `skill.admin.token` 独立鉴权 | 这个配置项是否已经存在（grep 一下）？如果没有，PR1/PR2 需要新增对应拦截器；这点是否被 Requirement 3 / PR 拆分覆盖？ |

### 3. 寻找新引入的盲点

特别看：

- **`AssistantScopeDispatcher` 改 API 的兼容性**：旧 `getStrategy(AssistantInfo)` 是否真的能委托新 API 不挂？grep 所有 caller 看看（应该有 3-4 处）。
- **`SkillSessionController.createSession` 调用顺序**：入口规则查找 → 注入 → 落 DB → 本地生成 toolSessionId → 不发 CREATE_SESSION。这条顺序里有没有事务边界被破坏的风险？toolSessionId 写 Redis 映射这一步要不要也跳过？
- **`SkillMessageController.routeToGateway` 命中规则后**：构造 payload + 发 invoke。这条路径 +1 次 ruleService.lookup，调用频次高（每次 chat），AtomicReference snapshot 性能够吗？
- **deletion check 短路简化方案**（Requirement 11）：用 `ruleService.lookup(session.domain, session.type)` 代替 `findByAssistantAccount(account)`——这是否真的覆盖所有路径？比如 createSession 入口对 request.assistantAccount 的 check（line 92-101）是不是已经在 D1 判定之后了，自然不会跑到？
- **`cloud_route_fallback_v2` 与 SS 这边 `cloud_protocol_profile` 命名"两套 profile 配置在两个服务"** 的运维心智成本——SOP 怎么写才不混？

### 输出格式

```markdown
# Codex 评审 (第 3 轮) — noauth-conversation-permission PRD

## 摘要（≤ 5 行）
[整体判断 + 是否可进入 PR1]

## 上一轮 Critical 关闭情况
- C1 → [已闭合 / 部分闭合 / 未闭合]：行号证据
- C2 → ...
- C3 → ...
- C4 → ...

## 本轮新变化的风险评估
- 改变 1 ：风险等级 + 具体证据
- ...

## 新引入的盲点（如有）
### N1：症状 / 根因 / 建议 / 影响 AC

## 总评结论
- 是否可以进入 PR1？
- 若不能，先做哪几件事？
```

中文输出。所有判断指向**具体文件 + 行号**或**决策号 (D1-D11)** 或 **Requirement 编号**。

## ground-truth 必读文件

SS:
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/profile/CloudRequestProfileRegistry.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`

GW:
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SysConfigFallbackProvider.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebHookExecutor.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java` （line 685-735 invoke 路由片段）

PRD: `.trellis/tasks/05-15-noauth-conversation-permission/prd.md`
