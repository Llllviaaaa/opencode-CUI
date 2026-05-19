# Research: PR1 Impact Analysis — Delete Legacy Source Relay Branch

- **Query**: Hard impact analysis for PR1 (drop the ai-gateway Legacy route branch that supports old, no-instanceId Source services)
- **Scope**: internal
- **Date**: 2026-05-19

> 注：本任务对 GitNexus index 的多次调用被 hook 旁路返回了"related symbols"摘要而非完整 d=1/d=2 impact 树；下面每个符号的 callers/refs 通过 `Grep` + file 内容二次验证给出来源行，等价于 d=1 实证。整份报告以"调用者列表 + 风险标签"形式落到 d=1 粒度，足以判断 PR1 是否可开工。

---

## A. 整类删除（incoming refs 全清单）

### A.1 `LegacySkillRelayStrategy` （`ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java`）

主代码 incoming refs（**全部来自 SkillRelayService，全部在 PR1 删除范围**）：
- `SkillRelayService.java:61` — 字段 `private final LegacySkillRelayStrategy legacyStrategy;`
- `SkillRelayService.java:132` — 构造器入参
- `SkillRelayService.java:138` — `this.legacyStrategy = legacyStrategy;`
- `SkillRelayService.java:172` — `legacyStrategy.registerSession(session)`
- `SkillRelayService.java:215-216` — `legacyStrategy.removeSession(session)` + `removeSessionSender`
- `SkillRelayService.java:337` — `legacyStrategy.relayToSkill(message)`（PR1 删 `relayToSkill` 并行投递）
- `SkillRelayService.java:684` — `legacyStrategy.handleInvokeFromSkill`
- `SkillRelayService.java:1025` — `legacyStrategy.getActiveConnectionCount()`
- `SkillRelayService.java:1190` — `legacyStrategy.refreshOwnerHeartbeat()`

测试 incoming refs（PR1 同步删除）：
- `SkillRelayServiceTest.java:37,56,423,431,451,458,468,475,485,499`（Mockito mock + behavior verify）
- `SkillRelayServiceV2Test.java:45,68,441,454,594`
- `LegacySkillRelayStrategyTest.java`（整文件随类删）

**风险**: **LOW** — 仅 legacy 主代码 + legacy 测试调用，整个删除安全。

### A.2 `SkillRelayStrategy` 接口（`ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayStrategy.java`）

incoming refs：
- 接口实现：`LegacySkillRelayStrategy implements SkillRelayStrategy`（PR1 一起删）
- 静态常量 `STRATEGY_ATTR`、`LEGACY`、`MESH` 使用处：
  - `SkillRelayService.java:171` — `session.getAttributes().put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.LEGACY)`
  - `SkillRelayService.java:176` — `put(STRATEGY_ATTR, MESH)`
  - `SkillRelayService.java:1226-1227` — `isLegacySession` 内部读取

`MESH` 标记在删除 legacy 后失去意义（不再有第二种策略，无需打标）。整接口可删；如果 PR1 想最小化改动，也可仅保留 `MESH` 常量（不推荐）。

**风险**: **LOW** — 接口的所有引用都在 PR1 删除范围内。

---

## B. SkillRelayService 内部字段/方法/常量删除

| 符号 | 定义位置 | 主代码 callers | 风险 |
|---|---|---|---|
| `legacyStrategy` 字段 | `SkillRelayService.java:61` | 见 A.1 | LOW |
| `legacyRelayEnabled` 字段 | `SkillRelayService.java:96` | 仅 `SkillRelayService.java:828` (`dispatchToAgent` 内的 `if (legacyRelayEnabled) { redisMessageBroker.publishToAgent(...) }`) | **MEDIUM** — 见下 |
| `isLegacyClient` | `SkillRelayService.java:1217` | 仅 `registerSourceSession:170` | LOW |
| `isLegacySession` | `SkillRelayService.java:1225` | `removeSourceSession:214`, `handleInvokeFromSkill:683` | LOW |
| `refreshLegacyOwnerHeartbeat` | `SkillRelayService.java:1189` (@Scheduled) | Spring 调度，无显式调用方 | LOW |
| `routeCache` 字段 | `SkillRelayService.java:83` | `learnRoute:282,286`, `invalidateRoutesForSession:297,303`, `learnRouteFromUpstream:615,618`, `resolveSourceType:992` (deprecated), `evictStaleRouteCache:1199,1205,1208`, `destroy:1239` | LOW — 全是私有/自调用 |
| `learnRoute(String, String, WebSocketSession)` | `SkillRelayService.java:280` (**public**) | 内部：`learnRouteFromInvoke:919`；测试：`SkillRelayServiceTest.java:264-265` | LOW — public 但仅自身 + 单测调用 |
| `learnRouteFromInvoke` | `SkillRelayService.java:916` | `handleInvokeFromSkill:707`, `routeBusinessInvoke:729` | LOW — 内部自调，删除时同步去掉两处调用 |
| `learnRouteFromUpstream` | `SkillRelayService.java:613` | **无调用方**（dead code） | LOW |
| `invalidateRoutesForSession` | `SkillRelayService.java:295` | `removeSourceSession:263` | LOW — 主路径上，需要在 `removeSourceSession` 里同步删除 |
| `evictStaleRouteCache` | `SkillRelayService.java:1197` (@Scheduled) | Spring 调度 | LOW |
| `resolveSourceType(GatewayMessage)` (private @Deprecated) | `SkillRelayService.java:978` | **无调用方**（dead code） | LOW |
| `inferSingleActiveSourceType` | `SkillRelayService.java:1003` | 仅 `resolveSourceType(...):1000` | LOW — 随 `resolveSourceType` 一起删 |
| `WELINK_ROUTE_PREFIX` 常量 | `SkillRelayService.java:85` | `learnRoute:286`, `learnRouteFromUpstream:618` | LOW |

**B.MEDIUM 详解 — `legacyRelayEnabled`**

`SkillRelayService.java:828-831`：
```java
if (legacyRelayEnabled) {
    redisMessageBroker.publishToAgent(ak, agentMessage);
    log.info("[EXIT->LEGACY] Also published to legacy agent channel: ak={}", ak);
}
```
含义：当 Agent 不在本 GW 且不在其它 GW 时（V2 三层都找不到），原代码会在 enqueueToPending 之外**额外**走一次 `publishToAgent`（即 `agent:{ak}` pub/sub），让仍订阅该 channel 的旧版 Agent 兜底接收。

删除影响：旧版 Agent（同时订阅 `agent:{ak}` pub/sub 的、不走 pending queue 的）将丢失这条兜底路径。

判断：PR1 的前提是"旧版 source 已下线 + 旧版 agent 都迁完"。配置当前默认 `GATEWAY_LEGACY_RELAY_ENABLED:true`，说明生产可能仍有依赖。**这条不是单纯代码 dead code，是运行时行为**。需要确认线上是否有未升级的 Agent 还在依赖 `agent:{ak}` pub/sub 兜底——这是 PR1 的关键运行时风险。

---

## C. RedisMessageBroker 待删方法

| 方法 | 定义位置 | 主代码 callers | 风险 |
|---|---|---|---|
| `publishToLegacyRelay` | `RedisMessageBroker.java:380` | `LegacySkillRelayStrategy.java:157`；deprecated 别名 `publishToRelay:801` | LOW |
| `subscribeToLegacyRelay` | `RedisMessageBroker.java:390` | `LegacySkillRelayStrategy.java:375`；deprecated 别名 `subscribeToRelay:806` | LOW |
| `unsubscribeFromLegacyRelay` | `RedisMessageBroker.java:399` | `LegacySkillRelayStrategy.java:252, 399`；deprecated 别名 `unsubscribeFromRelay:811` | LOW |
| `getActiveSourceOwners` | `RedisMessageBroker.java:843` | `LegacySkillRelayStrategy.selectOwner:335` | LOW |
| `refreshSourceOwner` | `RedisMessageBroker.java:815` | `LegacySkillRelayStrategy.refreshOwnerState:387` | LOW |
| `removeSourceOwner` | `RedisMessageBroker.java:825` | `LegacySkillRelayStrategy.clearOwnerState:394` | LOW |
| `instanceIdFromOwnerKey` (static) | `RedisMessageBroker.java:886` | `LegacySkillRelayStrategy.selectOwner:341`；内部 `getActiveSourceOwners:857` | LOW |
| `bindAgentSource` | `RedisMessageBroker.java:895` | `LegacySkillRelayStrategy.handleInvokeFromSkill:200` | LOW |
| `getAgentSource` | `RedisMessageBroker.java:903` | `LegacySkillRelayStrategy.resolveMessageSource:309` | LOW |

副带可顺手清理的 deprecated 别名（PR1 范围内推荐一起删）：
- `publishToRelay:800`、`subscribeToRelay:805`、`unsubscribeFromRelay:810`
- `hasActiveSourceOwner:835`、`removeAgentSource:911`、`sourceOwnerMember:872`、`sourceFromOwnerKey:877`、`agentSourceKey:986`、常量 `SOURCE_OWNER_KEY_PREFIX:75`、`SOURCE_OWNERS_SET_PREFIX:77`、`AGENT_SOURCE_KEY_PREFIX:79`、私有方法 `sourceOwnerKey:976`、`sourceOwnersSetKey:981`

测试 callers（一起改/删）：
- `RedisMessageBrokerTest.java:174-198`（publishToLegacyRelay / subscribeToLegacyRelay / unsubscribeFromLegacyRelay 三组用例）
- `LegacySkillRelayStrategyTest.java`：整文件随类删

**风险**: 整组 LOW —— 没有 legacy 之外的引用。

---

## D. 边界确认：必须保留的方法

### D.1 `RedisMessageBroker.publishToAgent` （`RedisMessageBroker.java:295`）

callers（验证 PRD 假设）：
1. `LegacySkillRelayStrategy.java:203` — **legacy 内部**，PR1 随类删
2. `SkillRelayService.java:829` — **`dispatchToAgent` 内 `if (legacyRelayEnabled)` 分支** — 见 B.MEDIUM，PR1 删
3. `EventRelayService.java:257` — **`relayToAgent(String ak, GatewayMessage message)` 公共方法** — 不在 PR1 范围内，**必须保留**

`EventRelayService.relayToAgent` 是个公共入口（外部业务调用 EventRelayService → publishToAgent → 走 `agent:{ak}` pub/sub）。GitNexus 也确认了 `publishToAgent` 的非 legacy caller 是 `dispatchToAgent`（即 SkillRelayService.java:829 那条 `if (legacyRelayEnabled)`），这一条与 D.1.2 是同一回事。

**结论**: `publishToAgent` 本身**不能删**，因为 `EventRelayService.relayToAgent` 是个公共 API；同理 `subscribeToAgent` / `unsubscribeFromAgent` 也不能删（`EventRelayService.java:182, 205` 在 `registerAgentSession` / `removeAgentSession` 里使用，属于核心 Agent 注册流程）。

**风险**: **HIGH**（如果 PR1 真的把 `publishToAgent` 误判为"legacy"删掉）—— 但 PRD 已经明确把它放在 D 段"必须保留"，所以只要 PR1 严格按 PRD 范围走，就是 LOW。这里写明确认结果是为了让实现者拿到时不犹豫。

---

## E. EventRelayService 内部排查

`EventRelayService.java` 中的 legacy 触点：

| 行号 | 内容 | 是否需要改 |
|---|---|---|
| 66 | javadoc: "Otherwise treat as legacy raw GatewayMessage JSON (backward compatibility)" | 文档残留，PR1 可顺手清理 |
| 79 | javadoc: "Distinguishes new-format ... from legacy raw GatewayMessage" | 同上 |
| 84-115 | `handleGwRelayMessage` 里 `if (rawJson.contains("\"type\":\"relay\""))` 走新协议；否则把 rawJson 当作 legacy `GatewayMessage` 兜底 | **属于 RelayMessage 自身的协议兼容**（来自 V2 切换期，与"老版 Source 服务"无关），**与 PR1 删除的"legacy source 路由分支"是两件不同的事**。**PR1 不要动**，留到协议层后续清理。 |
| 112 | 注释 "Legacy format: raw GatewayMessage JSON" | 同上，不要动 |
| 114 | log "legacy-format relay" | 同上，不要动 |

incoming/outgoing 速览：
- 入站：`@PostConstruct subscribeToSelfRelayChannel`（监听 `gw:relay:{instanceId}`）；外部 controller / WebSocket handler 调用 `registerAgentSession` / `relayToSkillServer` / `relayToAgent`
- 出站：`SkillRelayService.setEventRelayService`（构造时反向注入）、`SkillRelayService.relayToSkill`、`SkillRelayService.handleToSourceBroadcastRelay`、`SkillRelayService.findLocalSourceConnection`、`RedisMessageBroker.publishToAgent/subscribeToAgent/unsubscribeFromAgent`

**结论**: EventRelayService **不订阅 `gw:legacy-relay:{instanceId}`**（订阅的是 `gw:relay:{instanceId}`，由 RedisMessageBroker.subscribeToGwRelay 处理）。Legacy relay channel 的唯一订阅方是 `LegacySkillRelayStrategy.ensureRelaySubscription:373-377`，随 A.1 一起删除即可。EventRelayService 在 PR1 中**无需改代码**，只需要顺手清理 javadoc / 注释（可选）。

**风险**: **LOW** —— EventRelayService 不依赖 legacy source 路由。

---

## F. 配置项 `gateway.legacy-relay.enabled`

repo grep 命中：

| 文件 | 行号 | 内容 | PR1 处理 |
|---|---|---|---|
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java` | 95 | `@Value("${gateway.legacy-relay.enabled:false}")` | 删字段 |
| `ai-gateway/src/main/resources/application.yml` | 58-61 | `legacy-relay: enabled: ${GATEWAY_LEGACY_RELAY_ENABLED:true}` + 中文注释 | 删配置段 + 注释 |
| `.trellis/tasks/05-19-gw-cleanup-legacy-route-rename-questionid/prd.md` | 82, 128 | PRD 自身引用 | 不动 |
| `docs/superpowers/specs/2026-03-25-route-decoupling-v3-design.md` | 321, 510, 537 | 设计文档 | 不动（历史文档） |
| `docs/superpowers/specs/2026-03-24-route-decoupling-design.md` | 332, 515, 518, 1533 | 设计文档 | 不动 |
| `docs/superpowers/plans/2026-03-25-route-decoupling-impl.md` | 321, 365 | 实施计划文档 | 不动 |

注意 `application.yml` 默认值是 `true`（生产环境默认开启），**PR1 删除前确认线上已无依赖**。

---

## G. 测试受影响清单

| 测试文件 | 现状 | PR1 处理 |
|---|---|---|
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategyTest.java` | 全是 legacy 策略的单测 | **整文件删除** |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceTest.java` | L37/56 mock legacyStrategy；L259-265 测 `service.learnRoute`；L423/431/451/458/468/475/485/499 验证 legacy 分支行为 | **大改**：删 legacyStrategy mock + 构造器参数；删 `learnRoute` 单测；删 isLegacy / handleInvokeFromSkill legacy 分支 / 并行投递 等所有 legacy 用例 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java` | L45/68/441/454/594 引用 legacyStrategy | **改**：删 mock legacyStrategy 字段、调整构造器、删 L441-454 "Legacy fallback" 用例、L594 用 0 也可去掉 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerTest.java` | L174-198 三组 legacy relay channel 单测 | **删** 这三个用例（其余保留） |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java` | L109-110 注释 "v3: 不再查 getAgentSource"；L139 `verify(...).publishToAgent(...)` | 保留 publishToAgent 验证（来自 `relayToAgent`，非 legacy）；可顺手把过时注释删掉 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/SkillWebSocketHandlerTest.java` | L185/194 仅 displayName/注释里出现 "learnRoute" 字样 | 文案微调（可选） |

`SkillRelayService` 的构造器签名将从 6 参变 5 参（去掉 `LegacySkillRelayStrategy`），**所有 `new SkillRelayService(...)` 直接构造的测试**都要改：`SkillRelayServiceTest.java:56`、`SkillRelayServiceV2Test.java:68`。

---

## Go / No-Go 总结

**结论：PR1 可以开工，但实现前必须先解决 1 个 MEDIUM 风险点。**

- A、C、D、E、G 章节全部 LOW：纯删除 + 测试同步删，没有藏在非 legacy 路径里的"惊喜调用方"。`publishToAgent` / `subscribeToAgent` / `unsubscribeFromAgent` 必须保留（被 `EventRelayService.relayToAgent / registerAgentSession / removeAgentSession` 使用），PRD 已正确识别。
- B 章节唯一的 MEDIUM 是 `legacyRelayEnabled` 守护的那段 `dispatchToAgent` 内 `redisMessageBroker.publishToAgent(ak, agentMessage)` 的"兜底广播"——它是**运行时行为**，不是死代码。生产 yml 默认 `true`。
- F 章节确认 yml 默认值 `true`，与 SkillRelayService `@Value(":false")` 的默认值**不一致**——线上行为以 yml 为准（即默认走兜底）。

**开工前必须确认（HIGH 升级触发条件）**：
1. 线上是否仍有未升级的旧版 Source/Agent 依赖 `agent:{ak}` pub/sub 兜底？如果是，PR1 不能直接删 B 的 `legacyRelayEnabled` 分支，应先把 `application.yml` 默认值降到 `false`，灰度观察 1 个版本，再做 PR1。
2. 灰度灰得动的话，PR1 删除步骤建议：先合并 yml 改 `false` + 加 deprecation 日志的小 PR；观察后再合 PR1 大删除。

如果以上两点已经在前置 PR 中处理过（PRD 暗示已处理），PR1 即可按 PRD 范围全部 LOW 风险地开工，**没有需要保留或拆分的符号**。
