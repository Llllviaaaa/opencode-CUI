# GW 清理 Source 路由 Legacy 分支 + question.requestId → questionId 全链路重命名

## Goal

两件互相独立的清理：

1. 删掉 ai-gateway 里给"不带 instanceId 的旧版 Source 服务"用的 Legacy 路由分支（`LegacySkillRelayStrategy` + `SkillRelayService` / `RedisMessageBroker` / `EventRelayService` 里相关分支）。前提：线上所有 Source 服务都已升级，全部带 instanceId 握手。
2. 把 `StreamMessage.QuestionInfo.requestId` 全链路重命名为 `questionId`：skill-server Java 字段 + JSON 协议名 + skill-miniapp TS + plugin TS + v1/v2/v3 协议文档 + 所有相关测试。

Cloud Route v1 (`LegacyRouteResolver`) **不动** —— 它名字叫 Legacy 但运行时仍是 v1 fallback 路径，未到清理时机。

## What I already know

### Legacy Source 路由分支（Scope A）

入口文件：

- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java` —— 整文件删
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayStrategy.java` —— 接口本身可删，因为只剩 Mesh 一种策略
- `ai-gateway/src/test/java/.../LegacySkillRelayStrategyTest.java` —— 整文件删

`SkillRelayService.java` 里待删 / 待简化的点：

- `legacyStrategy` 字段 + 构造参数
- `legacyRelayEnabled` `@Value` 字段 + `dispatchToAgent` 末尾"也发 legacy agent channel"分支
- `STRATEGY_ATTR` 注入：`registerSourceSession` / `removeSourceSession` 里 `isLegacyClient` / `isLegacySession` 分支
- `relayToSkill` 里 "Legacy 路径：parallel-delivery" 整段
- `handleInvokeFromSkill` 里 `isLegacySession` 分支
- `getActiveSourceConnectionCount` 加上 legacy 计数
- `refreshLegacyOwnerHeartbeat` 整个定时方法
- `routeCache`、`learnRoute`、`learnRouteFromUpstream`、`learnRouteFromInvoke`、`invalidateRoutesForSession`、`evictStaleRouteCache`、`resolveSourceType`(@Deprecated)、`inferSingleActiveSourceType` —— 这一坨标记了 `@Deprecated`，原因是 V2 用 `UpstreamRoutingTable` 取代了，**需要确认全部仅服务于旧逻辑后再删**
- `WELINK_ROUTE_PREFIX` 常量、`isLegacyClient` / `isLegacySession` 方法

`RedisMessageBroker.java` 里待删的方法：

- `publishToLegacyRelay` / `subscribeToLegacyRelay` / `unsubscribeFromLegacyRelay`
- `getActiveSourceOwners` / `refreshSourceOwner` / `removeSourceOwner` / `instanceIdFromOwnerKey`
- `bindAgentSource` / `getAgentSource`
- `publishToAgent` —— **不能删**，`EventRelayService` 仍在用（line 257）

`EventRelayService.java`：检查里面是否还有 legacy 订阅 / publishToAgent 之外的 legacy 触点。

### question.requestId → questionId 重命名（Scope B）

后端入口：

- `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`：`QuestionInfo.requestId` 字段（`@JsonUnwrapped` 平铺到顶层）
- `skill-server/.../OpenCodeEventTranslator.java`：构造该字段处
- `skill-server/.../SkillMessageController.java`：使用处

前端 / plugin / 测试 / 协议文档涉及文件（基于 grep `requestId`）：

- `skill-miniapp/src/protocol/types.ts`、`StreamAssembler.ts`、`hooks/useSkillStream.ts`、`utils/api.ts`、`app.tsx`
- 组件：`QuestionCard.tsx`、`SubtaskBlock.tsx`、`MessageBubble.tsx`、`ConversationView.tsx`
- plugin：`message-bridge/src/contracts/downstream-messages.ts`、`adapter/OpencodeSessionGatewayAdapter.ts`、`port/SessionScopedActionGatewayPort.ts`、`protocol/downstream/DownstreamMessageNormalizer.ts`、`action/QuestionReplyAction.ts`
- plugin-openclaw：`runtime/QuestionRegistry.ts`、`OpenClawGatewayBridge.ts`、`session/upstreamEvents.ts`、`runtime/InteractionPorts.ts`
- 协议文档：`documents/protocol/v3/02-skillserver-gateway.md`、`v3/04-plugin-opencode.md`、`v3/05-opencode-to-custom-protocol-mapping.md`、`v3/06-end-to-end-flows.md`、以及 v1/v2 同名文档（如果保留历史不动则只改 v3）
- 测试：上面所有路径下的对应 test 文件

注意点：字段语义是 "opencode question request id"——既然 miniapp / plugin 也都改名，注释里"plugin 快路径 POST /question/{requestID}/reply"的 URL 命名要不要同步改也得确认（建议保留 URL，只改字段名，因为 URL 是 opencode 上游接口，不由本仓库决定）。

## Assumptions

- 线上 Source 服务全部带 instanceId（用户已确认）
- v1/v2 协议文档历史快照不改，只改 v3 当前版本（待用户确认）
- opencode 上游 `/question/{requestId}/reply` URL 路径不改（不归本仓库管）

## Decisions (locked)

- **协议文档**: 只改 v3，v1/v2 历史快照不动
- **`@Deprecated` 路由缓存**: 随 PR1 一起清（`routeCache` / `learnRoute*` / `evictStaleRouteCache` / `resolveSourceType`(@Deprecated) / `inferSingleActiveSourceType`），V2 `UpstreamRoutingTable` 已完整接管
- **PR2 边界**: **不改 plugin**。`QuestionReplyResultData.requestId`（plugin contracts）保留原名；`message-bridge-openclaw` 整包（QuestionRegistry / OpenClawGatewayBridge / InteractionPorts / upstreamEvents 及其测试）不动。PR2 只改 skill-server 后端字段 + skill-miniapp 前端 + v3 协议文档
- **PR0 跳过**: 已验证 `agent:{ak}` channel 的订阅方只有 `EventRelayService.registerAgentSession`（agent 连上 GW 才订阅）。`SkillRelayService:829` 的 legacy 兜底只在 agent 完全离线时触发——此时 channel 无订阅方，publish 到 dev/null。删除不会丢任何消息，**无需 PR0 灰度**，直接合 PR1

## Requirements

### Scope A — 删 Source 路由 Legacy 分支

- 删 `LegacySkillRelayStrategy.java` + 测试
- `SkillRelayStrategy` 接口删除（只剩 Mesh）
- `SkillRelayService` 移除所有 legacy 分支、`@Deprecated` 路由缓存、相关定时任务
- `RedisMessageBroker` 移除 legacy 专用方法（保留 `publishToAgent`）
- `EventRelayService` 移除 legacy 订阅 / handler
- 配置项 `gateway.legacy-relay.enabled` 从代码里删，application.yml 同步清掉
- 所有受影响的测试同步删 / 改

### Scope B — question.requestId → questionId 全链路改名

- `StreamMessage.QuestionInfo.requestId` 改名为 `questionId`，`@JsonProperty("questionId")` 让 JSON 也改名
- 后端构造 / 消费处全部跟随
- skill-miniapp 全链路字段名 + 类型 + 测试 fixture
- plugin (message-bridge + message-bridge-openclaw) 全链路
- v3 协议文档同步；v1/v2 历史快照不动
- 所有相关测试同步

## Acceptance Criteria

- [ ] `git grep -i "LegacySkillRelay\|legacyStrategy\|legacy-relay\|legacyRelayEnabled\|publishToLegacyRelay\|bindAgentSource\|getAgentSource\|getActiveSourceOwners\|refreshSourceOwner\|removeSourceOwner\|instanceIdFromOwnerKey"` 在 ai-gateway 主代码 + 测试下零结果
- [ ] `routeCache` / `learnRoute` 等 `@Deprecated` 路由缓存代码删干净
- [ ] `git grep "requestId" skill-server skill-miniapp plugins documents/protocol/v3` 命中处只能是非 question 域的 requestId（需要列出 diff 给用户）
- [ ] ai-gateway 全模块 `./mvnw test` 通过
- [ ] skill-server 全模块 `./mvnw test` 通过
- [ ] skill-miniapp `npm test` 通过
- [ ] plugins 两个 bundle 测试通过
- [ ] 用 `gitnexus_impact` 对所有删/改符号跑过一遍，没有遗漏的 d=1 调用方

## Definition of Done

- 测试 / lint / typecheck / CI 全绿
- Scope A 在 PR1，Scope B 在 PR2，互相独立
- PRD 收口后、开 PR1 前用 codex (gpt-5.5 + xhigh) 评审一轮 (per memory: feedback_codex_design_review)

## Out of Scope

- Cloud Route v1 (`LegacyRouteResolver`) 不动
- v1/v2 协议文档历史快照不动
- opencode 上游 `/question/{requestId}/reply` URL 不改（不归本仓库）
- 跟 legacy Source 无关的 `@Deprecated` 代码不动

## Technical Approach

两个独立 PR，可并行：

**PR1 (Scope A)** —— 纯删除 + 简化：
1. `LegacySkillRelayStrategy.java` + test 删除
2. `SkillRelayService` 简化：移除 legacy 字段 / 分支 / 定时任务 / 路由缓存
3. `SkillRelayStrategy` 接口删除
4. `RedisMessageBroker` 删除 legacy 方法（保留 publishToAgent）
5. `EventRelayService` 同步清理
6. `application.yml` 移除 `gateway.legacy-relay.enabled`
7. 所有受影响测试同步

**PR2 (Scope B)** —— 字段重命名：
1. `StreamMessage.QuestionInfo.requestId` → `questionId` + `@JsonProperty`
2. skill-server 后端调用点同步
3. skill-miniapp 字段 / 类型 / 组件 / 测试同步
4. plugin (message-bridge + openclaw) 同步
5. v3 协议文档同步
6. 测试 fixture / mock 同步

## Implementation Plan

- **PR1**: Source 路由 Legacy 清理（先做，blast radius 大但纯删除）
- **PR2**: question.requestId 重命名（PR1 合并后或并行，前提是不碰同一文件）

## Technical Notes

- `routeCache` 这块虽然挂着 `@Deprecated`，但 `learnRouteFromInvoke` 还在 `handleInvokeFromSkill` 主路径里被调；删之前必须确认 V2 `UpstreamRoutingTable.learnRoute` 已完整覆盖，不会丢路由信息
- 删 `SkillRelayStrategy` 接口前需要确认 `STRATEGY_ATTR` / `LEGACY` / `MESH` 常量是否仅用于路由分支判断；若仅此用途，跟接口一起删
- `gitnexus_impact` 在每个待删 / 改符号上跑一遍是 hard requirement（CLAUDE.md 约束）
