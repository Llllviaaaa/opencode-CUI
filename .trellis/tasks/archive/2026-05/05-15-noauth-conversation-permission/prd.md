# 无 ak 与 assistantAccount 场景的会话与权限能力

## Goal

为某个特定接入方提供 7 项 miniapp 等价能力：创建会话 / 列表 / 详情 /
历史 / 对话 / 反问回复 / 权限回复 / 终止 / 关闭——但客户端**不传**
`ak` 也**不传** `assistantAccount`，仅传 `(businessSessionDomain,
businessSessionType, businessSessionId)` + cookie userId。

服务端按 `(businessSessionDomain, businessSessionType)` 在 sys_config
规则表里查"默认助手"绑定，把虚拟的 (ak, assistantAccount, businessTag)
注入 SkillSession，让后续会话走与现有业务助手广场（business scope）
对称的云端 HTTP 路径——只不过身份是 SS / GW 协同的"虚拟"身份，上游资产服务**不识别**它。

## Background

### miniapp 通道现状（关键代码锚点）

- `SkillSessionController.createSession` (line 113-135)：ak 非空时按 scope 走真实助手分支；ak 空 → 创建空壳 session（仅 DB）
- `SkillMessageController.routeToGateway` (line 170-242)：`if (session.getAk() == null) return;` → 没 AI 回复
- `SkillMessageController.replyPermission` (line 397)：`if (session.getAk() == null) return 400`
- `GatewayRelayService.sendInvokeToGateway` (line 107-115)：按 scope 分两条路径——`business` 走 strategy.buildInvoke，其它走本地 `buildInvokeMessage`
- `BusinessScopeStrategy.buildInvoke` (line 65-150)：从 payload 取 assistantAccount / sendUserAccount，调 `profileRegistry.resolve(businessTag)` 选 cloud profile，包装 invoke message
- `CloudRequestProfileRegistry.resolve(businessTag)` (line 62-72)：按约定 fallback `profile.name() == businessTag == strategyName`
- `AssistantSquareCloudRequestStrategy.build` (line 88-101)：assistantAccount / sendUserAccount blank 即 IAE

### ai-gateway 侧现状

- `SkillRelayService.handleInvokeFromSkill` (line 689-692)：`assistantScope=business` → `routeBusinessInvoke` → `BusinessInvokeRouteStrategy` → `CloudAgentService.handleInvoke`
- `CloudAgentService.handleInvoke` (line 72-142)：
  - 取 `payload.cloudProfile` 决定 SSE decoder
  - 调 `callbackConfigService.getConfig(ak, scope)` 拿 cfg（channelAddress / channelType / authType）
  - **action 白名单**：chat / question_reply / permission_reply；close_session / abort_session 不在 `ACTION_TO_SCOPE` map → 报 unknown action
  - 按 channelType 分叉：SSE / WebSocket 走 streaming；webhook 走 `WebHookExecutor`
- `CallbackConfigService` (line 87-120) + `SysConfigFallbackProvider` (line 63-96)：v2 resolver 上游失败时按 SS `sys_config[cloud_route_fallback:{chat|question|permission}]` 兜底——**全局**，只按 scope 分，不区分 ak / businessTag

### 现状结论：能力差距

- 创建 / 列表 / 详情 / 历史：DB 路径，✅
- 用户消息持久化 + ws 广播：✅
- chat AI 回复 / 反问 / 权限审批 / 权限回复：❌（ak=null 直接 SKIP / 400）
- 即使强行把 (ak, assistantAccount) 灌进 session，下游也卡在 4 个上游绊脚石：
  1. `AssistantInfoService.getAssistantInfo(virtualAk)` → 上游不识别 → null
  2. `AssistantScopeDispatcher.getStrategy(null)` → personal fallback → 找 PCAgent → 失败
  3. `BusinessScopeStrategy.buildInvoke` 读 `info.businessTag` → null 时空指针
  4. `SysConfigFallbackProvider` 只按 scope 分、无法区分 cloudProfile

## Decisions（基于本轮 brainstorm 收敛）

### D1：触发条件 + 全场景优先级矩阵（修订自 codex N2）

**判定时机**：createSession 入口 + sendMessage / replyPermission / close / abort 入口都做同款 `ruleService.lookup(session.domain, session.type)`。

**优先级矩阵（统一适用所有 endpoint）**：

| 客户端入参 / session 状态 | 规则查找结果 | 行为 |
|---|---|---|
| `request.ak` 或 `request.assistantAccount` 非空 | 任意（**规则不查**） | **永远走老路径**（显式 override；现有 personal/business 路径不变） |
| 两个都为空 + `domain`、`type` **都非空且精确匹配命中规则** | 命中 | 默认助手路径（注入 + 单事务落 DB + 本地 toolSessionId） |
| 两个都为空 + 其它（任一为空 OR 都非空但不命中） | 未命中 | createSession → **400 `ak 和 assistantAccount 必填`**；其它 endpoint 不会走到此处（session 已存在则字段已固化） |

**精确匹配语义**：`domain` 和 `type` 必须**同时非空、同时精确匹配** sys_config 配置才视为命中——任何形式的缺失（任一为空）或不匹配，一律视为"未命中"。`ruleService.lookup(domain, type)` 内部：null/blank 入参直接返 `Optional.empty()`；sys_config 查询用 `key={domain}:{type}` 字面比对。不做通配 / 大小写归一 / 模糊匹配（参见 Out of Scope）。

**注（codex N2 修订）**：D1 优先级矩阵**取代**现有 `AssistantAccountResolverService.isSkipOnNullAssistantAccount()` 开关在"裸创建空壳 session"场景下的默认行为——本任务上线后：
- 旧调用方传 ak 或 assistantAccount → 行为不变
- 旧调用方什么都不传想"裸创建空壳" → **不再被现有开关 default-true 放行**，统一 400

这是为了消除"以为创建了能用 session、实际没 AI 回复"的灰色体验。
若运维确实需要保留"裸创建空壳"的能力，可以在规则表里配 `(domain, type) → 真业务 ak`，但这是显式配置不是隐式 fallback。

### D2：规则表 3 字段（最小集）

```
sys_config:
  config_type   = "default_assistant_rule"
  config_key    = "{businessSessionDomain}:{businessSessionType}"
  config_value  = JSON {"ak":"AK_V","assistantAccount":"ACC_V","businessTag":"assistant_square"}
  status        = 1   ← 启用
```

| 字段 | 用途 |
|---|---|
| `ak` | 虚拟 ak。写回 `SkillSession.ak`。Strategy 内部按它反查规则拿 businessTag |
| `assistantAccount` | 虚拟 assistantAccount。写回 `SkillSession.assistantAccount`。也塞进 cloud_request.assistantAccount 给云端识别 |
| `businessTag` | SS 内部喂给 `CloudRequestProfileRegistry.resolve(...)` 选 CloudRequestProfile；wire 上以 `payload.cloudProfile` 字段传给 GW |

**不存** cloudEndpoint / cloudProtocol / authType / ownerWelinkId：
- 前三个是 GW 的 `cloud_protocol_profile` SysConfig 管的，跨服务契约约定 SS 只传 profile name 字符串
- `ownerWelinkId` 是 external/IM 入站场景为了兜底 sendUserAccount 用的；miniapp 通道有 cookie userId，不需要

### D3：复用 miniapp 现有 `/api/skill/sessions/*` 入口

不开新 endpoint，cookie userId 鉴权，external 通道完全不动。

### D4：新增 `DefaultAssistantScopeStrategy`（独立 scope）

与 personal / business 并列的第三种 scope strategy：

| 维度 | 取值 |
|---|---|
| `getScope()` | `"default_assistant"`（本地 dispatcher 路由识别用） |
| `buildInvoke` | 仿 BusinessScopeStrategy.buildInvoke：strategy 内部按 `cmd.ak()` 反查规则取 `businessTag`，其它字段从 cmd.payload 抽 |
| 发给 GW 的 `assistantScope` | wire 层填 `"business"`（让 GW 按业务路径处理；commit b2e8940 路径） |
| 发给 GW 的 `payload.cloudProfile` | `profile.name()`（约定下等于 rule.businessTag） |
| `generateToolSessionId()` | Snowflake long 数值字符串（与 business 一致） |
| `requiresOnlineCheck()` | false |
| `translateEvent()` | 复用 `CloudEventTranslator` |

### D5：dispatcher 统一收口（修订自 codex N1，方案 B / Strategy Pattern 单一职责）

**为什么收口到 dispatcher**：codex N1 揭示——`GatewayRelayService.sendInvokeToGateway` 拿 virtual ak 查 `AssistantInfo` 返 null，老 dispatcher `getStrategy(info)` 当 personal 处理，**绕开 default strategy**。

第一直觉是"caller 自己 `findByAk(ak)` bypass"，但这违反 Strategy Pattern 单一职责（"caller 不该自己挑 strategy"）。本任务**把 strategy 选择全部收口到 dispatcher**，caller 只调 dispatcher。

**`InvokeCommand` 加 `domain` / `domainType` 字段**（兼容构造器：老 caller 不传 → 默认 null，行为不变）。

**`AssistantScopeDispatcher` 新 API**：

`AssistantScopeStrategy getStrategy(String domain, String domainType, AssistantInfo info)`
- 先 `ruleService.lookup(domain, domainType)`：命中即返 `DefaultAssistantScopeStrategy`
- 不命中 → 委托老 `getStrategy(info)` 逻辑（personal / business / 兜底）

老 API `getStrategy(AssistantInfo info)` 保留 `@Deprecated`，新 API 内部委托老 API；或老 API 内部委托新 API 时 domain/domainType 传 null（不会命中规则，行为完全不变）。两种委托方向都行，避免递归即可。

**3 个调用点（含定位）**：

| 调用点 | 用途 | 调谁 |
|---|---|---|
| **Controller 入口**（createSession / sendMessage / replyPermission / close / abort） | **业务路径选择**——决定调"普通 createSession 还是 createSessionWithDefaultAssistant"、close/abort 是否跳 GW、payload 是否补字段 | `ruleService.lookup(domain, type).isPresent()` 即可（不需要选 strategy；只是判"是不是默认助手会话"） |
| **`GatewayRelayService.sendInvokeToGateway`** | **选 strategy**（决定 buildInvoke 哪个实现） | `scopeDispatcher.getStrategy(cmd.domain(), cmd.domainType(), info)` —— **不再自己 findByAk** |
| **`DefaultAssistantScopeStrategy.buildInvoke` 内部** | **取详情**（取 businessTag、assistantAccount） | `ruleService.lookup(cmd.domain(), cmd.domainType())` —— 这是取详情，不是选 strategy，符合 Strategy Pattern |

→ Service 仅暴露一个反查方法（方案 C 简化后）：
- `Optional<Rule> lookup(String domain, String domainType)` —— controller 业务路径选择 + dispatcher 选 strategy + strategy 内部取详情，**三处都用同一个方法**。InvokeCommand 加了 domain/domainType 字段后，strategy 内部不需要 `findByAk(ak)` 反查。

null/blank 入参一律返 `Optional.empty()`。

**为什么 controller 不也走 dispatcher**：controller 决策的是"业务流程分叉"（调哪个 service 方法 / 是否补 payload 字段），不是"strategy 选哪个"。两者目的不同——dispatcher 收口的是"选 strategy"这一件事。

**N1 漏洞为什么堵上**：因为 service 层（`GatewayRelayService`）也调 dispatcher 新 API，dispatcher 内部统一 `lookup(domain, domainType)` 反查，virtual ak 上游不存在不影响——只看 domain/type 命不命中规则。

**代价**：每次 invoke 多 1 次内存 map lookup（dispatcher 内）。可忽略。InvokeCommand 加 2 字段、~15 处 caller，但**保留兼容构造器，只有"会走到默认助手路径"的 caller**（SkillSessionController / SkillMessageController 几处）**实际需要塞值**；其它 IM / external / rebuild caller 一行不动。

### D6：toolSessionId 本地预生成 + service 层一个事务（修订自 codex N4）

业务类云端是无状态 webhook 形态，没有"远程 toolSession"实体——SS 本地 Snowflake 即可。
**不调** `gatewayRelayService.sendInvokeToGateway(... CREATE_SESSION ...)`。

**codex N4 补充**：当前 `SkillSessionController.createSession` 是"`sessionService.createSession()` 落 DB（事务 1）→ controller 拿到 sessionId 再 `sessionService.updateToolSessionId()`（事务 2 + Redis mapping）"分两次调。中间窗口里 session 有 ak 但无 toolSessionId（且无 Redis `toolSessionId→sessionId` 映射），上行事件若按 toolSessionId 反查（`GatewayMessageRouter.java:1047-1070`）会找不到。

**修法**：在 `SkillSessionService` 新增 `createSessionWithDefaultAssistant(...)` 方法，**单事务**完成：
1. INSERT skill_session（含 ak, assistantAccount, toolSessionId）
2. createRoute 写 session ownership
3. Redis 写 `toolSessionId → sessionId` 映射（复用 `updateToolSessionId` 内部逻辑，但合并到事务里）
返回 session 对象。

Controller 入口判定后直接调这个新方法；老路径（personal / 显式 business）继续走两次调用，**不强制重构老逻辑**（避免回归面变大）。

### D7：close_session / abort_session 在默认助手路径下**不发 GW**

仅在 SS DB 标 `status=CLOSED`。理由：GW `CloudAgentService.ACTION_TO_SCOPE` 不含 close/abort，发过去会回 `Unknown action` TOOL_ERROR。该判定顺手修复了未来真业务助手接入 miniapp 时的 latent bug——不算回归。

### D8：4 处 payload 补 `assistantAccount` + `sendUserAccount`

| 位置 | 现状 payload | D8 补 |
|---|---|---|
| `SkillMessageController.routeToGateway` chat 分支 | 已含 | — |
| `SkillMessageController.routeToGateway` QUESTION_REPLY 分支 | 缺 | `qr.put("assistantAccount", session.getAssistantAccount()); qr.put("sendUserAccount", effectiveUserId);` |
| `SkillMessageController.replyPermission` | 缺 | 同上 |
| `SkillSessionController.closeSession / abortSession` payload | 缺（且 D7 后不发 GW） | D7 之后不再发，本项无需修 |

也就是说实际只补 **2 处**（question_reply 分支 + replyPermission），close/abort 因 D7 跳过整个 invoke。

数据源：`session.getAssistantAccount()`（createSession 时已存）+ cookie userId（已有 `userIdCookie` / `effectiveUserId` 变量）——**不引入新的数据源 / 新的 RPC**。

### D9：GW 侧 fallback 按 cloudProfile 区分（新 config type，与老 fallback 互不混用）

| 维度 | 老 fallback（保留） | 新 fallback（本任务加） |
|---|---|---|
| config_type | `cloud_route_fallback` | `cloud_route_fallback_v2`（命名可议） |
| config_key | `{scope}`（如 `chat`） | `{cloudProfile}:{scope}`（如 `assistant_square:chat`） |
| 适用 | cloudProfile 缺失 / `"default"` | cloudProfile 非 `"default"` 的具体值 |
| 拿不到时 | 报 `callback_config_missing` | 报 `callback_config_missing`（**不**再 fallback 到老） |

互不兜底——运维清单要清晰，谁该配什么。

### D10：GW 改动范围（最小，新老 cache key 分立 —— 风险 1 修订）

**关键：新老两套 cache key 完全独立**——老调用方上线后**零 cold miss**。

| cloudProfile 取值 | cache key 格式 | provider | sys_config type |
|---|---|---|---|
| `"default"` / `null` / blank（老调用方） | `gw:cloud:route:{version}:{ak}:{scope}`（**完全不动**） | 老 `SysConfigFallbackProvider` | `cloud_route_fallback:{scope}` |
| 具体值（如 `"assistant_square"`，本任务默认助手） | `gw:cloud:route:v2:{version}:{ak}:{scope}:{cloudProfile}` | 新 `SysConfigFallbackProviderV2` | `cloud_route_fallback_v2:{cloudProfile}:{scope}` |

| 改的文件 | 改什么 |
|---|---|
| `CallbackConfigService.getConfig` | 签名加 `cloudProfile` 参数；入口按 `cloudProfile == "default" / null / blank` 二选一：走老路径 / 走新路径；**老路径整套不动**（含 cache key / provider / sys_config type） |
| `CloudAgentService.handleInvoke` (line 96) | 把 cloudProfile 传进 `callbackConfigService.getConfig` |
| 新增 `SysConfigFallbackProviderV2` | 查 `cloud_route_fallback_v2:{cloudProfile}:{scope}`，miss 直接返 null |
| `SysConfigFallbackProvider`（老的） | **完全不动** |

**不动**：v2 resolver / SkillRelayService 主流程 / decoder 链 / WebHookExecutor。

**上线影响**（风险 1 闭合）：
- 老业务助手 cloudProfile 不传 → 走老 cache key 命中率不变 → **零 cold miss**
- 新默认助手 cloudProfile = `"assistant_square"` → 走新 cache key（独立空间），第一次自然 cold miss 一次（符合预期，因为是新调用方）
- 新老 cache key 互不干扰、互不污染、互不兜底

### D11：运维侧需要新加 / 启用的 sys_config

```
type=cloud_route                       key=v2_enabled              value="1"   ← 启用 v2 path（已有机制）
type=cloud_route                       key=v2_fallback_enabled     value="1"   ← 启用 fallback（已有机制）

type=default_assistant_rule            key="helpdesk:direct"
                                       value={"ak":"AK_V","assistantAccount":"ACC_V","businessTag":"assistant_square"}
                                       ── (domain, type) 一行；本任务新加 ──

type=cloud_route_fallback_v2           key="assistant_square:chat"
                                       value={"channelAddress":"<SSE URL>","channelType":"sse","authType":"soa"}
type=cloud_route_fallback_v2           key="assistant_square:question"
                                       value={"channelAddress":"<webhook URL>","channelType":"webhook","authType":"soa"}
type=cloud_route_fallback_v2           key="assistant_square:permission"
                                       value={"channelAddress":"<webhook URL>","channelType":"webhook","authType":"soa"}
                                       ── 每 cloudProfile × 每 scope 一行；本任务新加 ──
```

## Requirements

### SS 侧

1. **新增 model**：`record DefaultAssistantRule(String ak, String assistantAccount, String businessTag)`，canonical constructor 做 3 字段非 blank 校验。`domain` / `domainType` **不**放在 record 里——它们是 sys_config key 拼接成分（`{domain}:{domainType}`），不在 value JSON 里，与 D2 表格一致。

2. **新增 service**：`DefaultAssistantRuleService`（薄壳，复用 `SysConfigService` Redis 5 分钟缓存）
   - 构造器注入：`SysConfigService` / `ObjectMapper`
   - **不**自管内存 snapshot、**不**周期 reload、**不**启动加载、**不**启动校验、**不**提供反查 ak 方法。
   - 暴露**唯一**方法：
     ```
     Optional<DefaultAssistantRule> lookup(String domain, String domainType) {
         if (blank(domain) || blank(domainType)) return Optional.empty();
         String json = sysConfigService.getValue("default_assistant_rule",
                                                 domain + ":" + domainType);
         if (json == null) return Optional.empty();
         try {
             return Optional.of(objectMapper.readValue(json, DefaultAssistantRule.class));
         } catch (JsonProcessingException e) {
             log.warn("[RuleService] invalid JSON for {}:{}: {}",
                      domain, domainType, e.getMessage());
             return Optional.empty();
         }
     }
     ```
   - **缓存机制**：`SysConfigService.getValue` 内部已有 Redis 缓存（TTL 默认 5 分钟，由 `SysConfigProperties.cacheTtlMinutes` 配置），`update` 操作自动 evict —— 运维改规则用现有 `/api/admin/configs/**` update 接口即可，下次 lookup 自动拿新值。
   - **关键省略**（codex N3 / 风险 4 / M3 同时闭合）：`AtomicReference` snapshot + 60s 周期 reload + generation CAS + `TaskScheduler` + admin reload endpoint + `skill.admin.token` + `AdminTokenAuthInterceptor` + 启动校验 + `findByAk(ak)` 反查 —— **全部不需要**。
   - **运行时容错**：JSON 解析失败 / 字段缺失 → log warn + 返 empty（等同于"未命中"），用户得到 400 `ak 和 assistantAccount 必填`。错误在第一次 lookup 时暴露（无启动校验）。
3. **删除项**（原 PRD 这里曾计划新增 admin reload endpoint + 相关 token 与拦截器，现因方案 C 全部移除）：
   - ~~admin reload endpoint `POST /api/admin/default-assistant-rules/reload`~~
   - ~~新配置项 `skill.admin.token`~~
   - ~~新拦截器 `AdminTokenAuthInterceptor`~~
   - ~~选项 A / B token 隔离策略~~
   - **运维流程**：改规则用现有 `SysConfigController.update()` 接口（路径 `/api/admin/configs/**`）—— `SysConfigService.update` 自动 evict 缓存，下次 lookup 拿新值。
   - **codex 风险 4（admin token）自动闭合**：本任务不引入任何新的 admin 配置项 / 拦截器。

4. **新增 scope strategy**：`DefaultAssistantScopeStrategy`（见 D4）

5. **改 `AssistantScopeDispatcher`**（dispatcher 收口，方案 B）：
   - 新增 `AssistantScopeStrategy getStrategy(String domain, String domainType, AssistantInfo info)`：
     ```
     Optional<DefaultAssistantRule> rule = ruleService.lookup(domain, domainType);
     if (rule.isPresent()) {
         return defaultAssistantScopeStrategy;
     }
     return getStrategy(info);   // 委托老 API
     ```
   - 老 API `AssistantScopeStrategy getStrategy(AssistantInfo info)` 保留**不动**（不标 @Deprecated；它仍是合法的"只按 info 选 strategy"入口，给不接 domain/type 的 caller 用）。
   - 新 API 内部委托老 API 即可——避免互相递归。
   - 新 caller：`GatewayRelayService` 调新 API。其它现有 caller（如 inboundProcessing、sessionRebuild）继续调老 API，行为不变。

6. **改 `InvokeCommand` + `GatewayRelayService.sendInvokeToGateway`**（修订自 codex N1，方案 B）：
   - **`InvokeCommand` record 加 `String domain` / `String domainType` 两个字段**：
     - 放在 record 末尾保持向后兼容。
     - 保留现有 5 参数和 6 参数构造器：`domain` / `domainType` 默认 null。
     - 新构造器接 7 / 8 参数（带 domain/domainType + 可选 suppressReply）。
     - 老 caller（IM / external / sessionRebuild / 测试）一行不改——domain/type 为 null，dispatcher `lookup(null, null)` 返 empty，走老 strategy 选择。
   - **`SkillMessageController` / `SkillSessionController` 构造 InvokeCommand 时塞入 session 字段**：
     ```
     new InvokeCommand(session.getAk(), userId, sessionId, action, payload,
                       null,  // suppressReply
                       session.getBusinessSessionDomain(),
                       session.getBusinessSessionType());
     ```
   - **`GatewayRelayService.sendInvokeToGateway`** (line 107-115)：
     - **删除**老的 `scopeDispatcher.getStrategy(info)`（旧 API）。
     - **改调** `scopeDispatcher.getStrategy(command.domain(), command.domainType(), info)` —— 让 dispatcher 收口反查规则。
     - **不在此自己 `findByAk`**——方案 B 收口要求 strategy 选择只在 dispatcher 一处。
     - 改后流程：
       ```
       AssistantInfo info = assistantInfoService.getAssistantInfo(command.ak());
       AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
           command.domain(), command.domainType(), info);
       // strategy 可能是 default_assistant / business / personal
       if (strategy.getScope().equals("business")
               || strategy.getScope().equals("default_assistant")) {
           message = strategy.buildInvoke(command);
       } else {
           message = buildInvokeMessage(command);   // personal 本地构造
       }
       ```

7. **新增 `SkillSessionService.createSessionWithDefaultAssistant(...)`**（修订自 codex N4，方案 A 一次性全写入）：
   - 签名：`SkillSession createSessionWithDefaultAssistant(String userId, String ak, String assistantAccount, String title, String businessDomain, String businessType, String businessSessionId, String toolSessionId)`
   - 方法标注 `@Transactional`，**一次性**完成：
     1. INSERT skill_session（含 ak / assistantAccount / toolSessionId / userId / 业务字段 / status=ACTIVE）
     2. createRoute 写 session ownership（与现有 `createSession` 同款副作用）
     3. Redis 写 `toolSessionId → sessionId` 映射
   - **Redis 写失败处理（宽松策略，由用户拍板）**：
     - Redis 写 try-catch：失败 → `log.warn("[createSession] Redis mapping failed for toolSessionId={}, sessionId={}: {}", ...)`，**MySQL 事务仍提交**。
     - 影响：极小窗口内若 GW 已回上行事件按 toolSessionId 反查 sessionId，会找不到映射 → 这条消息被丢弃。但 DB session 已就绪，后续请求走 controller 入口规则反查仍能恢复。
     - 兼顾"Redis 拖头时建会话不全 fail"的可用性 vs"一致性"的折中。
   - 返回最新 session 对象（**from-DB 重读**，避免 ORM 二级缓存返回旧版本）。
   - 老 `createSession` + `updateToolSessionId` 两步路径**保留不动**，给 personal scope / 显式 business ak 使用——它们有 GW CREATE_SESSION 响应时序保护，本任务不重构。

8. **改 `SkillSessionController.createSession`**（D1 + D6 + 修订自 codex N5）：
   - **在 deletion check 之前**（即 line 82 之前）插入规则查找：
     ```
     // D1 优先级矩阵
     boolean hasExplicit = !blank(request.ak) || !blank(request.assistantAccount);
     if (!hasExplicit) {
         // 任一为空时 lookup 内部直接返 empty，与"不命中"等价处理
         Optional<Rule> rule = ruleService.lookup(
             request.businessSessionDomain, request.businessSessionType);
         if (rule.isEmpty()) {
             return 400 "ak 和 assistantAccount 必填";
         }
         // 命中规则：注入 + 走默认助手单事务路径
         String snowflake = defaultAssistantScopeStrategy.generateToolSessionId();
         SkillSession session = sessionService.createSessionWithDefaultAssistant(
             userId, rule.ak(), rule.assistantAccount(), request.title,
             request.businessSessionDomain, request.businessSessionType,
             request.businessSessionId, snowflake);
         return ApiResponse.ok(session);   // 直接返单事务后的最新对象，不重读
     }
     // 老路径：显式 ak/assistantAccount → 现有 deletion check → 现有 createSession 流程
     ```
   - 注意：规则注入路径完全短路 deletion check（virtual assistantAccount 上游本就不存在，check 必然 NOT_EXISTS）。

9. **改 `SkillMessageController.routeToGateway`**（D1 + D8）：
   - 入口 `ruleService.lookup(session.businessSessionDomain, session.businessSessionType)`
   - 命中：进入默认助手 payload 构造分支（chat 已有 assistantAccount/sendUserAccount；question_reply 分支补这俩字段）→ sendInvokeToGateway
   - 未命中：走老路径

10. **改 `SkillMessageController.replyPermission`**（D8）：payload `pr` map 补 `assistantAccount` + `sendUserAccount`（与 routeToGateway 同款）

11. **改 `SkillSessionController.closeSession` / `abortSession`**（D7）：
    - 入口 `ruleService.lookup(session.businessSessionDomain, session.businessSessionType)`
    - 命中：跳过 `gatewayRelayService.sendInvokeToGateway(CLOSE_SESSION / ABORT_SESSION)`，直接 `sessionService.closeSession(sessionId)`
    - 未命中：走老逻辑（personal scope 仍发给 PCAgent）

12. **deletion check 短路**（修订自 codex N5）：
    - **createSession**：D1 优先级矩阵已经把规则注入路径放在 deletion check 之前（Requirement 8 实现），不需要单独短路——规则命中即整段绕开老 createSession 流程。
    - **sendMessage / replyPermission** 入口的 `checkAssistantDeletion*` 调用：在调 `assistantAccountResolverService.check(account)` **之前**，先判 `ruleService.lookup(session.businessSessionDomain, session.businessSessionType).isPresent()` → 命中即 PASS。
    - **不引入** `findByAssistantAccount(account)` 方法（避免第三种反查；用 (domain, type) 同款判定）。

### GW 侧

13. **改 `CallbackConfigService.getConfig`**（D10，风险 1 修订 —— 新老 cache key 分立）：
    - 签名加 `cloudProfile` 参数。
    - 入口分派：
      ```
      if (cloudProfile == null || cloudProfile.isBlank() || "default".equals(cloudProfile)) {
          return getConfigV1(ak, scope);   // 老路径：cache key + provider 完全不动
      }
      return getConfigV2(ak, scope, cloudProfile);   // 新路径：v2 cache key + v2 provider
      ```
    - 老路径 `getConfigV1`：现有实现一行不改（cache key `gw:cloud:route:{version}:{ak}:{scope}` + 老 `SysConfigFallbackProvider`）。**老调用方上线零 cold miss**。
    - 新路径 `getConfigV2`：新 cache key `gw:cloud:route:v2:{version}:{ak}:{scope}:{cloudProfile}` + 新 `SysConfigFallbackProviderV2`。

14. **改 `CloudAgentService.handleInvoke`** (line 96)：把 cloudProfile 传进 `callbackConfigService.getConfig(...)`。

15. **新增 `SysConfigFallbackProviderV2`**：查 `sys_config[cloud_route_fallback_v2:{cloudProfile}:{scope}]`，miss 直接 null。

### 文档

16. `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md` 加"默认助手注入"小节
17. `.trellis/spec/skill-server/backend/database-guidelines.md` 加 sys_config 新 type 约定
18. 新建运维 SOP `docs/superpowers/specs/2026-05-15-default-assistant-rule-ops.md`：
    - 规则模板（3 字段 JSON 示例：ak / assistantAccount / businessTag）
    - 用现有 `/api/admin/configs/**` update 接口增删启停（SysConfigService 自动 evict 缓存）
    - 与 GW `cloud_route_fallback_v2` 的对应关系（强约束 `profileName == businessTag`，见 Known Issues #2）
    - **部署后强制验证 checklist**（N6 补丁，取代代码层校验）：新增 / 修改 `default_assistant_rule` 后，运维必须在 5 分钟内跑完三个 smoke test：
      1. `POST /api/skill/sessions` 用对应 `(domain, type)` 不传 `ak` / `assistantAccount` → 预期 200，DB `ak` 写入虚拟值
      2. `POST .../messages` 发一条 chat → 预期 SSE 回流 `text.delta` / `text.done`，**不报** `callback_config_missing`
      3. `POST .../messages` 带 `toolCallId` 发 question_reply → 预期 webhook 200，**不报** `callback_config_missing`
      任一步失败 → 回滚配置 + 排查（大概率 SS `cloud_protocol_profile` 缺、或 GW `cloud_route_fallback_v2` 缺）。

## Acceptance Criteria

### A. 规则查找与缓存（复用 SysConfigService）

- [ ] sys_config 启用一行合法规则 `(helpdesk, direct) → {ak, assistantAccount, businessTag}` → `ruleService.lookup("helpdesk", "direct").isPresent() == true`，字段与 sys_config 完全一致
- [ ] `lookup` null / blank 入参（domain 或 type 任一空）→ 返 `Optional.empty()`，不抛 NPE，**不**调 `SysConfigService.getValue`
- [ ] sys_config 不存在的 (domain, type) → `lookup` 返 `Optional.empty()`
- [ ] sys_config value 是非法 JSON → `lookup` 返 `Optional.empty()` + 日志 WARN `[RuleService] invalid JSON for {domain}:{type}: ...`
- [ ] **缓存命中**：第一次 `lookup("helpdesk", "direct")` → Redis miss → 查 DB → 写 Redis；第二次同 key → Redis 直接命中，**不查 DB**
- [ ] **缓存失效**：调 `SysConfigService.update` 改规则 → 对应 Redis cache key 自动 evict → 下次 lookup 走 DB 拿新值
- [ ] **缓存 TTL**：`SysConfigProperties.cacheTtlMinutes`（默认 5 分钟）后 Redis 自然过期，下次 lookup 走 DB
- [ ] **Redis 故障降级**：mock Redis 抛异常 → `SysConfigService.getValue` 降级直查 DB → `lookup` 仍能拿到值（不阻塞）

### B. 7 个 endpoint 端到端（配 `(helpdesk, direct) → (AK_V, ACC_V, assistant_square)`）

`POST /api/skill/sessions` （domain/type 命中规则、不传 ak/assistantAccount）：
- [ ] 200，DB `ak=AK_V` / `assistantAccount=ACC_V` / `toolSessionId=<snowflake>` / status=ACTIVE
- [ ] 日志含 `[INFO] createSession: rule-injected, domain=helpdesk, type=direct, ak=AK_V`
- [ ] 不发 CREATE_SESSION 给 gateway（mock gateway 端不收到 invoke 消息）
- [ ] 响应里 session 字段与 DB 一致（重读返回）
- [ ] **单事务 + Redis 一次写入**（codex N4）：成功路径 → DB skill_session / route ownership / Redis `toolSessionId→sessionId` 映射**三处同时就绪**，无中间窗口
- [ ] **Redis 失败宽松**：mock Redis 写抛异常 → DB 事务仍 commit（status=ACTIVE）、Redis 映射缺失；日志含 `[WARN] [createSession] Redis mapping failed for toolSessionId=...`；接口仍返 200

`POST /api/skill/sessions` （domain/type 未命中规则 + 不传 ak/assistantAccount）→ 400 `ak 和 assistantAccount 必填`

`GET /api/skill/sessions?ak=AK_V` / `?assistantAccount=ACC_V`：均能查到
`GET /api/skill/sessions/{id}`：字段完整
`GET .../messages` / `messages/history`：分页返 200

`POST .../messages` （chat）：
- [ ] payload 含 `assistantAccount=ACC_V` + `sendUserAccount=<cookieUserId>`
- [ ] strategy.buildInvoke 产物：`assistantScope="business"` + `payload.cloudProfile="assistant_square"`
- [ ] gateway 端 mock `cloud_route_fallback_v2:assistant_square:chat` 配置（sse） → 走 SSE → 回流 `text.delta` / `text.done` → ws push 给客户端

`POST .../messages` 带 `toolCallId` （question_reply）：
- [ ] payload 含 `assistantAccount` + `sendUserAccount`（D8 修复）
- [ ] gateway 走 webhook（mock `cloud_route_fallback_v2:assistant_square:question`）→ 2xx → fire-and-forget

`POST .../permissions/{permId}` （permission_reply）：
- [ ] 不再 400
- [ ] payload 含 `assistantAccount` + `sendUserAccount`
- [ ] gateway 走 webhook（mock `cloud_route_fallback_v2:assistant_square:permission`）

`DELETE /api/skill/sessions/{id}` （close） / `POST .../abort`：
- [ ] DB 标 `status=CLOSED`
- [ ] **不**发 CLOSE_SESSION / ABORT_SESSION invoke 给 gateway（mock 端无消息）

### C. 老路径不回归（按 D1 全场景优先级矩阵 + dispatcher 收口）

- [ ] 显式传 ak 调 createSession（domain/type 也命中规则）→ **不**走规则注入；走老路径；DB 中 ak 是显式值
- [ ] 显式传 assistantAccount → 同上
- [ ] 都没显式 + 规则未命中（任一为空 OR 都非空但 sys_config 无对应 key）→ **400 `ak 和 assistantAccount 必填`**
- [ ] 现有 `AssistantAccountResolverService.isSkipOnNullAssistantAccount()` 开关对**显式调用方**（传 ak/assistantAccount 的）行为不变；对"裸创建"路径**不再生效**（D1 接管）
- [ ] 现有 miniapp / external / IM 三通道测试集全绿
- [ ] **dispatcher 老 API 兼容**（方案 B 收口）：`scopeDispatcher.getStrategy(info)` 单参数 API 行为完全不变——IM / external / SessionRebuild 等老 caller 不修改也能继续正确路由 personal / business strategy
- [ ] **InvokeCommand 兼容构造器**（方案 B）：现有 5 / 6 参数构造器仍可用，`domain` / `domainType` 默认 null；老 caller 不传值时，dispatcher 新 API `getStrategy(null, null, info)` 内部 `lookup(null, null)` 返 empty，路由到老 strategy 选择路径——personal / business 行为完全不变
- [ ] **dispatcher 新 API 路由**（codex N1 收口）：构造 `InvokeCommand` 时 `domain="helpdesk"` / `domainType="direct"`（命中规则） + virtual ak（上游 `getAssistantInfo` 返 null）→ `GatewayRelayService` 调 `getStrategy(domain, domainType, info=null)` → 返 `DefaultAssistantScopeStrategy` → 走 `strategy.buildInvoke()`，**不**落 personal

### D. GW 侧 fallback 区分（v2 + new provider）

- [ ] GW 收到 invoke `cloudProfile="assistant_square", action="chat"` → 缓存 key `gw:cloud:route:v2:{version}:{ak}:chat:assistant_square` miss → v2 resolver null → 走新 fallback provider → 查 `cloud_route_fallback_v2:assistant_square:chat` → 命中
- [ ] GW 收到 invoke `cloudProfile="default"` / 缺失 → 走老 cache key `gw:cloud:route:{version}:{ak}:chat`（**老 key 不变**）→ 走老 fallback provider → 查 `cloud_route_fallback:chat`
- [ ] **老调用方零 cold miss**（风险 1 闭合）：发布前老 ak（cloudProfile=default）写入老 cache → 发布后第一次请求 → cache key 仍是 `gw:cloud:route:{version}:{ak}:{scope}` → **命中**（不需要预热脚本）
- [ ] 新老 cache key 互不污染：同一 ak / scope 不同 cloudProfile 各自独立 entry
- [ ] 新 fallback miss 时不再回查老 fallback；返 `callback_config_missing`
- [ ] 新 fallback 缓存 TTL 与老 fallback 对齐（按 version 主缓存控制）

### E. PR 中间态

- [ ] PR1（GW）独立合并发布后，真业务助手 / 老调用方行为完全不变
- [ ] PR2（SS 基础）独立合并后，无 caller 指向新代码（中间态）
- [ ] PR3（SS 接入）合并后默认助手能力上线
- [ ] PR4 文档 / SOP 同步

## Implementation Plan

### PR1 — GW 侧 fallback by cloudProfile（前置可发布）

修改 `CallbackConfigService.getConfig(ak, scope, cloudProfile)` 签名 + 入口分派（cloudProfile=default/null/blank → 老路径，其它 → 新路径，**老 cache key 完全不动**）；改 `CloudAgentService.handleInvoke` line 96 调用处；新增 `SysConfigFallbackProviderV2`。
单测：新老路径分派、新 cache key 格式、cloudProfile 缺失走老（老 cache key 不变）、**老调用方上线零 cold miss**。
对真业务助手 / 老调用方：cloudProfile == default 走老 provider → 行为不变。

### PR2 — SS 侧基础（无 caller 接入，中间态）

新增 `DefaultAssistantRule` model、`DefaultAssistantRuleService`（薄壳，复用 `SysConfigService.getValue` Redis 缓存）、`DefaultAssistantScopeStrategy`（实装版本，挂 dispatcher map 但无 caller 调用它）。
`InvokeCommand` record 加 `domain` / `domainType` 字段（兼容构造器保留，老 caller 一行不动）。
`AssistantScopeDispatcher` 加新 API `getStrategy(domain, domainType, info)`（新 API 内部先反查规则、命中即返 default_assistant；老 API `getStrategy(info)` 保留不动给现有 caller 继续用）。
单测全覆盖：rule loading / 反查 / scope strategy / dispatcher 新老 API 路由。

### PR3 — SS 侧接入（上线 PR）

新增（codex N4）：
- `SkillSessionService.createSessionWithDefaultAssistant(...)` 单事务方法（INSERT skill_session + createRoute + Redis toolSessionId 映射）

改：
- `GatewayRelayService.sendInvokeToGateway` (line 107-118)：调 dispatcher 新 API `getStrategy(domain, domainType, info)`（**不**自己 `findByAk`，方案 B 收口）；business / default_assistant 都走 `strategy.buildInvoke()`
- `SkillSessionController.createSession`：按 D1 优先级矩阵 + Requirement 8 顺序——显式 ak/assistantAccount 走老路径；规则命中走新单事务方法；都没显式且未命中 → 400；构造 InvokeCommand 时塞 domain/domainType（PR3 范围内只有 createSession 走老路径的分支需要——默认助手路径不发 invoke）
- `SkillMessageController.routeToGateway`：入口 `ruleService.lookup` 判定 + question_reply 分支 payload 补 assistantAccount + sendUserAccount + 构造 InvokeCommand 时塞 domain/domainType
- `SkillMessageController.replyPermission`：payload 补同两字段 + 构造 InvokeCommand 时塞 domain/domainType
- `SkillSessionController.closeSession` / `abortSession`：命中规则跳过发 GW invoke
- `SkillMessageController.checkAssistantDeletion*`：调 `assistantAccountResolverService.check` 前先判规则命中即 PASS（Requirement 12 收口）

### PR4 — 集成测试 + 文档 + 运维 SOP

mock gateway 测试覆盖 AC §B 全部；miniapp 协议规范 / database-guidelines 同步；新建运维 SOP。

## Out of Scope (MVP)

- 不动 external `/api/external/invoke` 通道（assistantAccount 必填契约不变）
- 不加 `SkillSession.bound_by_rule` 标记位（schema 不动）
- 不支持 (domain, *) / (*, type) 通配
- 不支持按 senderUserAccount / 用户分组维度命中
- 不对历史 ak=null session 做自动迁移回填
- 不引入"补绑老 session"的管理接口（之前 D9 草案撤回——若运营需要，下一期再加）
- 不引入 `cloud_route_fallback_v2` 到老 `cloud_route_fallback` 的自动 fallback
- 不动现有 `AssistantAccountResolverService` 的 skip-on-null 开关
- 不引入新的 admin endpoint / 拦截器 / token（运维改规则用现有 `/api/admin/configs/**` update 接口）
- virtual ak / assistantAccount 命名约定（防与真业务 ak 撞车）由运维自治；不强制前缀

## Known Issues / Future Evolution

1. **PCAgent 对 payload 多字段容忍性**（codex M-new-2 + round 3 验证）：D8 给 question_reply / replyPermission 加 assistantAccount + sendUserAccount。codex round 3 已验证 PCAgent 端 `DownstreamMessageNormalizer.ts:236-265, 284-328` 忽略未知字段（不会 reject）—— **解除 self-review M-new-2 担忧**。

2. **`profileName == businessTag` SOP 强约束**（codex N6）：本任务约定运维在 sys_config 配 `cloud_protocol_profile:{businessTag}` 时尽量保持 profileName 字符串等于 businessTag（即 commit b2e8940 的 default fallback 路径）。SOP 文档要把这条**写死在第一页**：
   ```
   规则表 businessTag → 同名 profileName → 同名 strategy/decoder
   规则表 businessTag → GW cloud_route_fallback_v2:{同名}:{scope}
   ```
   不遵守这个约定会导致 SS profile 选择和 GW fallback 配置错位，排障极困难。

3. **`cloud_route_fallback_v2` 与 `cloud_protocol_profile` 命名分裂**：GW 用 `cloud_route_fallback_v2`（按 cloudProfile 区分）；SS 内部用 `cloud_protocol_profile`（同样按 businessTag/cloudProfile 区分）。两份配置都按"profile"分维度，但 type 不同。SOP 文档需要清楚交代两边各管哪部分，避免运维混淆。

4. **virtual ak / 真业务 ak 撞车风险**：运维若把规则里的 ak 取成与某个真业务 ak 相同字符串，会造成 dispatcher 反查命中 default_assistant 而绕过真业务路径。建议运维约定 virtual ak 前缀（如 `DEFAULT_*`），但本任务不强制。

5. **TOCTOU**：sys_config UPDATE 规则与现 session 写入间，可能出现"createSession 看到旧规则、sendMessage 看到新规则"的窗口。当前设计：createSession 命中即写 DB（ak/assistantAccount 落库不变）；sendMessage 反查规则只用于"是不是默认助手会话"判定 + strategy 内部取 businessTag——businessTag 用新值即"运维改了规则即时生效"，符合预期。

6. **历史 ak=null 空壳 session 永远是死会话**（codex round 3 C4 决策闭合）：本任务 D9 已撤回"补绑接口"。所以本任务上线前已有的、ak=null 的空壳 session（之前老 `isSkipOnNullAssistantAccount=true` 放行创建出来的）仍然没法发消息——`SkillMessageController.routeToGateway` 仍按 `session.ak==null` 跳过 GW。这是产品已接受的折损（与 PRD Out of Scope 第 5 / 6 项一致）：不做迁移、不加补绑。运营如有需要可手工 SQL 回填 ak / assistantAccount 字段。

7. **deletion check 短路 → 未来可重构为装饰器**（codex N5 设计模式路标）：本任务 sendMessage / replyPermission 入口的 deletion check 短路（Requirement 12）用 caller 内 `if (ruleService.lookup(...).isPresent()) { skip; }` 实现 —— 简单、回归面小、本任务 2 处 caller 规模下不需要装饰器。**触发重构条件**：未来若 deletion check 复用面 ≥ 5 处 caller，建议提取 `VirtualAccountAwareDeletionCheck` 装饰器（在 `AssistantAccountResolverService.check` 外套一层）—— 这样不用在每个新 caller 里加 if 短路，符合 Open/Closed Principle。

8. **真业务助手 close/abort 仍踩 GW latent bug**（codex 风险 2 路标）：本任务只为命中规则的默认助手会话跳过 `CLOSE_SESSION / ABORT_SESSION` invoke（D7 + Requirement 11）。**真业务助手 ak 不命中规则 → 仍走老路径发 GW invoke → 仍踩 `CloudAgentService.ACTION_TO_SCOPE` 不含 close/abort 的 latent bug → 返 `Unknown action` TOOL_ERROR**。这是本任务**之前就存在**的问题，外部 `/api/external/invoke` 通道没触发是因为它走另一套 close 处理。**触发修复条件**：未来真业务助手接入 miniapp 通道时，须在 GW 把 `close_session` / `abort_session` 加进 `ACTION_TO_SCOPE` 并实现对应处理（或同步用"命中即跳 GW"策略），那才能消除 latent bug。

9. **监控指标**（OOS）：规则命中率 / SysConfigService 缓存命中率 / 默认助手 vs 真业务助手 invoke 占比、TOOL_ERROR `callback_config_missing` 频次——建议下一期加。

## Technical Notes

### 跨服务字段命名收敛

| 概念 | SS 内部命名 | Wire（SS→GW） | GW 内部消费 | sys_config key |
|---|---|---|---|---|
| 业务路由标签 | `businessTag` | `payload.cloudProfile` | `cloudProfile` 参数 | `cloud_protocol_profile:{businessTag}` (SS) / `cloud_route_fallback_v2:{cloudProfile}:{scope}` (GW) |

在 `CloudRequestProfileRegistry` 的"profile_def 缺失即等于"约定下，三者通常是同一字符串（如 `"assistant_square"`）。

### 关键代码锚点（基于本次评审，可能漂移）

SS 侧：
- `controller/SkillSessionController.java` (113-135, 197-209, 215-243, 92-101)
- `controller/SkillMessageController.java` (170-242, 213-221, 367-463, 473-521)
- `service/GatewayRelayService.java` (94-141, 148+)
- `service/scope/AssistantScopeDispatcher.java` (57-69)
- `service/scope/BusinessScopeStrategy.java` (65-150)
- `service/cloud/profile/CloudRequestProfileRegistry.java` (62-109)
- `service/SkillSessionService.java` (61-64 默认 miniapp, 71-75 route ownership, 228-240 updateToolSessionId)
- `model/InvokeCommand.java` (record，本任务加 `domain` / `domainType` 字段，保留兼容构造器)

GW 侧：
- `service/CloudAgentService.java` (72-142)
- `service/CallbackConfigService.java` (87-159)
- `service/SysConfigFallbackProvider.java` (33-96)
- `service/cloud/BusinessInvokeRouteStrategy.java` (22-40)
- `service/cloud/WebHookExecutor.java` (42-80)

### Codex 评审历史

- 评审 v1：`research/codex-review.md`（model=gpt-5.5, effort=xhigh）—— 4 Critical + 5 Major + 5 Minor。
- 评审 v2：未完成（codex 额度耗尽），手工 self-review 补齐发现 C-new-1（M3 EXISTS 校验与 virtual ak 上游不存在前提冲突）。
- 评审 v3：`research/codex-review-v3.md`（model=gpt-5.5, effort=xhigh，read-only 实读源码）。结论：
  - 上一轮 4 个 Critical 在源码层"全部未闭合"——只在 PRD 里写了修订，代码未动。
  - 新引入 4 个风险点中 N1 / N3 / 风险 4（admin token）评级 High。
  - 新引入 6 个盲点 N1–N6。
  - **PR1（GW fallback by cloudProfile）独立可启动**；PR2 / PR3 需先修 N1 / N2 / N3 才能进。
- 本版 PRD（v3 修订后）：
  - **N1 修订（方案 B / Strategy Pattern 收口）**：D5 改为"dispatcher 统一收口"——`AssistantScopeDispatcher` 新 API `getStrategy(domain, domainType, info)` 在内部 `ruleService.lookup` 反查；`GatewayRelayService` 调新 API。`InvokeCommand` record 加 `domain` / `domainType` 字段（兼容构造器保留，老 caller 不动）。Requirement 5 + 6 重写。配合方案 C：strategy 内部"取详情"也用 `lookup(domain, type)`（不需要 `findByAk(ak)`）。
  - **N2 修订**：D1 给出完整优先级矩阵，明确显式 ak/assistantAccount 永远 override 规则；domain/type 任一空一律 400；AC §C 取消"走老 skip 开关"表述。
  - **N3 修订（方案 C 简化）**：删除自管缓存 / 60s reload / generation CAS / TaskScheduler。`DefaultAssistantRuleService` 退化为 SysConfigService 薄壳，每次 `lookup` 走 `SysConfigService.getValue`（Redis 5 分钟 TTL，update 自动 evict）。**N3 整个问题不存在**。
  - **N4 修订**：D6 + Requirement 7 新增 `SkillSessionService.createSessionWithDefaultAssistant(...)` 单事务方法。
  - **N5 修订**：Requirement 8 + 12 收口——createSession 在 deletion check 之前完成判定；sendMessage/replyPermission 用 (domain, type) 同款判定，不引入第三种反查。
  - **N6 修订**：Known Issues 第 2 项写入 SOP 强约束（profileName == businessTag）。Requirement 18 补 smoke test checklist 取代代码层校验。
  - **风险 1 修订**：D10 + Requirement 13 改为"新老 cache key 完全分立"——`cloudProfile=default/null/blank` 走老 cache key `gw:cloud:route:{version}:{ak}:{scope}`（**完全不动**）+ 老 provider；其它走新 cache key `gw:cloud:route:v2:{version}:{ak}:{scope}:{cloudProfile}` + 新 provider。**老调用方上线零 cold miss**，不需要预热脚本。
  - **风险 4 修订（方案 C 简化）**：删除 admin reload endpoint + `skill.admin.token` + `AdminTokenAuthInterceptor`。运维改规则用现有 `/api/admin/configs/**` update 接口（SysConfigService.update 自动 evict 缓存）。**风险 4 整个问题不存在**——本任务不引入任何新 admin 配置项 / 拦截器。
  - **C4 决策闭合补充**：Known Issues 第 6 项明确"历史空壳 session 不救"。
- 本版状态：codex round 3 全部已知问题已在 PRD 层闭合。是否再跑 round 4 验证由开发方决定；不再跑也可以进 PR1（GW 侧，独立）。
