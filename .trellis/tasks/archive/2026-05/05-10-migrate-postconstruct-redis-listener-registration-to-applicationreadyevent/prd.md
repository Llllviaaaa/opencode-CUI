# Migrate PostConstruct redis listener registration to ApplicationReadyEvent

## Goal

修复 **`@PostConstruct` 阶段调 `RedisMessageBroker.subscribe()` 时 Spring 不真实把 SUBSCRIBE 发到 Redis** 的 lifecycle race。把所有 PostConstruct 阶段注册 Redis listener 的代码迁到 `@EventListener(ApplicationReadyEvent.class)`，确保 `RedisMessageListenerContainer` 已 `start()` 之后再 `addMessageListener`。

## Why this matters

PR #20 + PR #21 修了 `physicalSubscriberCount` 的 Lettuce decode bug 和 `scheduleWithFixedDelay` 立即触发的 bug，但**没修真根因**。线上 log 实测：

| 注册时机 | Channel | PUBSUB NUMSUB | 收消息 |
|---|---|---|---|
| `@PostConstruct` | `ss:relay:skill-server-local` | **0** ❌ | 0 |
| `@PostConstruct` | `ss:external-relay:skill-server-local` | (推测 0) | 0 |
| 运行期（WebSocket 连接到来） | `user-stream:1` | 推测 1 ✅ | 127 ✅ |

127 次接收消息**全部**来自 `user-stream:1`，PostConstruct 阶段注册的 channel 一条都没接到。

### 根因分析

`RedisMessageListenerContainer` 是 Spring `SmartLifecycle`，`autoStartup=true` 默认，**在所有 `@PostConstruct` 之后才 `start()`**。PostConstruct 阶段调 `addMessageListener` 时 container 还没 running，listener 加进 `channelMapping` 但不立即 SUBSCRIBE。

理论上 Spring 在 `container.start()` 时应该从 `channelMapping` 重新订阅 — 但**实测在 spring-data-redis 3.4.6 + Lettuce 6.4.2 下不真的发 SUBSCRIBE 到 Redis**。运行期（container 已 running）调 `addMessageListener` 同步触发 SUBSCRIBE，正常工作。

### 影响

无修复时：
- `ss:relay:{instanceId}` 长期 0 订阅 → SkillInstanceRegistry 自检失败 → `forceReconnectListenerContainer` 风暴（每 10s 一次）
- 多 pod 部署：实例间 `publishToSsRelay` 投递的消息**实际收不到**（pub/sub broadcast 时 Redis 没把消息送到本机），跨实例 takeover/relay 链路失效
- `ss:external-relay:{instanceId}`：external WS 入站消息无法跨 pod 路由

## What I already know

### 涉及的两处 PostConstruct

| 文件 | 行 | 注册的 channel |
|---|---|---|
| `GatewayMessageRouter.initSsRelaySubscription` | 271-276 | `ss:relay:{instanceId}` |
| `ExternalStreamHandler.subscribeRelayChannel` | 183-189 | `ss:external-relay:{instanceId}` |

两个 method 都是 stateless 单行 subscribe + log，无 PostConstruct 间依赖。

### 不涉及的 PostConstruct

`GatewayMessageRouter.initConfirmDedupCache` (line 284-292) 是初始化本地 Caffeine cache，**不**涉及 Redis listener，**不动**。

### 与 PR #21 的相互作用

PR #21 把 `SkillInstanceRegistry.refreshHeartbeat` 也迁到 `@EventListener(ApplicationReadyEvent.class)`，且 `firstRunAt = now + interval`（首次执行 10s 后）。

新一轮迁移后两者都监听 `ApplicationReadyEvent`：
- `GatewayMessageRouter.initSsRelaySubscription` → 调 `addMessageListener` → 同步 SUBSCRIBE
- `SkillInstanceRegistry.startScheduling` → 注册 schedule，10s 后才首次自检

PR #21 的 10s 首次延迟**已经**保护了执行顺序问题，所以**不需要 `@Order`**。但显式 `@Order` 更清晰、更稳。

## Assumptions

- 把 `@PostConstruct` 改成 `@EventListener(ApplicationReadyEvent.class)` 之后，container 已经 `start()`，`addMessageListener` 会同步触发 SUBSCRIBE → 修复生效
- 这个假设跟 `user-stream:1` 在运行期注册能正常工作的实测**一致**
- Spring `@EventListener(ApplicationReadyEvent.class)` 之间默认无序，但本次涉及的 method 之间没有相互依赖

## Decision (ADR-lite)

### D1. 修复策略：Approach A — 调用方改注解

**Context**: PostConstruct 阶段 `addMessageListener` 不真实 SUBSCRIBE。需要让 listener 注册发生在 `RedisMessageListenerContainer` 已 `start()` 之后。

**Decision**: 把涉及的 2 处 `@PostConstruct` 改为 `@EventListener(ApplicationReadyEvent.class)`。**不**改 `RedisMessageBroker` 内部封装。

**Rationale**:
- 改动最小（2 个注解 + 测试 mock signature）
- 跟 PR #21 已经迁移的 `SkillInstanceRegistry.startScheduling` 风格一致
- Approach B（broker buffer）虽然防未来踩坑但工程量大，且本项目目前只有 2 处 PostConstruct subscribe，封装收益不明显
- 用 spec 加固"PostConstruct 不能调 RedisMessageBroker.subscribe"反模式来防再犯（替代 Approach B 的封装兜底）

**Consequences**:
- 简单直接，但需要每个未来 contributor 知道这条规则（依赖 spec / code review）
- 如果未来需要加第三个 PostConstruct subscribe 调用方，再次需要做同款迁移 — spec 段会提示

### D2. `@Order`：不加

**Context**: 多个 `@EventListener(ApplicationReadyEvent.class)` 默认无序，理论上 SkillInstanceRegistry.startScheduling 可能比 GatewayMessageRouter.initSsRelaySubscription 先执行。

**Decision**: **不加** `@Order`。

**Rationale**:
- PR #21 的 `firstRunAt = now + interval`（10s 首次延迟）已经给 SUBSCRIBE 留了足够窗口
- 即使 startScheduling 先执行，10s 后第一次 fire 时 subscribe 一定已经完成
- 加 @Order 增加耦合（两个独立 component 隐式依赖）

**Consequences**:
- 如果未来 PR #21 的 firstRunAt 改回 immediate（不太可能），需要重新评估 @Order
- spec 段会提到这个隐式时序保证，避免别人无意中破坏

## Requirements (locked)

### R1. `GatewayMessageRouter.initSsRelaySubscription`
- `@PostConstruct` → `@EventListener(ApplicationReadyEvent.class)`
- method signature 加 `ApplicationReadyEvent event` 参数（跟 PR #21 的 `SkillInstanceRegistry.startScheduling` 一致；event 不用就不读）
- log 内容保持不变

### R2. `ExternalStreamHandler.subscribeRelayChannel`
- 同 R1 改造

### R3. 测试
- 找现有 `GatewayMessageRouter` 测试，看是否依赖 `initSsRelaySubscription` 的 PostConstruct 调用时机；如有则改为模拟 `ApplicationReadyEvent` 触发
- 找现有 `ExternalStreamHandler` 测试，同款处理
- 新增/调整测试：断言 `subscribeToSsRelay` / `subscribeToChannel` 在 `ApplicationReadyEvent` 之后才被调用，PostConstruct 阶段不调用

### R4. Spec 加固（防再犯）
- `.trellis/spec/skill-server/backend/conventions.md` 加一段反模式："PostConstruct 不能调 `RedisMessageBroker.subscribe()`"
- 引用根因：`RedisMessageListenerContainer` 是 SmartLifecycle，PostConstruct 阶段 container 还没 start，addMessageListener 不真实触发 SUBSCRIBE
- 强制使用 `@EventListener(ApplicationReadyEvent.class)`
- "禁止事项"表格加一行

### Out of Scope

- `RedisMessageListenerContainer` lifecycle 行为深究 — 只改调用方，不改 broker / container
- `forceReconnectListenerContainer` 自愈链路修复 — 修了 R1+R2 后理论上不再触发
- `RedisMessageBroker` 层 buffer 封装（Approach B）— 工程量大，spec 加固足以防再犯
- `@Order` 显式排序 — 见 D2，PR #21 的 10s firstRunAt 已经保护时序

## Acceptance Criteria

**功能**
- [ ] AC1：服务重启后 60s 内 log 无 `Self-check failed` / `Force reconnecting` / `Reconnect failed`（手工验收）
- [ ] AC2：启动期 `[ENTRY] GatewayMessageRouter.initSsRelaySubscription` 和 `[ENTRY] ExternalStreamHandler.subscribeRelayChannel`（或对应 log）出现在 `[ENTRY] startScheduling` 附近（都在 ApplicationReadyEvent 后），不在 `[ENTRY] register` 之前
- [ ] AC3：第一次 `[ENTRY] refreshHeartbeat` (~10s 后) 写入 `Self-healed via reconnect` 不出现，正常进入心跳循环

**测试**
- [ ] AC4：`GatewayMessageRouter` 测试改造，模拟 `ApplicationReadyEvent` 触发后才调 `subscribeToSsRelay`
- [ ] AC5：`ExternalStreamHandler` 测试改造，同款
- [ ] AC6：`mvn -pl skill-server test` 全绿，无新 deprecation warning

**Spec**
- [ ] AC7：`conventions.md` 新增"PostConstruct 不能注册 Redis listener"反模式段落 + "禁止事项"表格新增一行

## Definition of Done

- 测试：单测覆盖 + 手工验收（重启服务 60s 静默）
- Lint / typecheck：`mvn -pl skill-server compile test` 通过
- 文档：更新 `conventions.md` 加固"PostConstruct 不能注册 Redis listener"的反模式（防止再犯）
- 验证：跟 PR #21 协作下，启动期不再触发 `forceReconnectListenerContainer`

## Technical Notes

### 文件

- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` (line 271-276)
- `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java` (line 183-189)
- 对应测试文件待确认

### 项目栈

- Spring Boot 3.4 + Java 21
- spring-data-redis 3.4.6, lettuce-core 6.4.2.RELEASE

### 相关 PR

- PR #19 (merged): batch — ai-gateway TTL + docs
- PR #20 (merged): physicalSubscriberCount Lettuce decode + safe self-heal scheduling
- PR #21 (merged): defer first heartbeat refresh by interval

本次是 PR #20/#21 的真根因 follow-up — decode bug 修了之后才看清的更深层 race。
