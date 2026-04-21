# StreamMessageEmitter 重构设计

**日期**: 2026-04-21
**作者**: Llllviaaaa
**状态**: Draft — 待 review
**关联 bug**: external ws `type=error` 事件缺失 `welinkSessionId`，上游无法归属会话

---

## 1. 问题与动机

### 1.1 现象（Bug）

external ws 通道推出的 `type=error` 事件，在特定路径下 **不携带 `welinkSessionId`**，上游收到消息后无法定位对应会话。

### 1.2 根因定位

skill-server 的 StreamMessage 出站消息有一个约定：推送前调用 `GatewayMessageRouter.enrichStreamMessage(sessionId, msg)` 填充 `sessionId` / `welinkSessionId` / `emittedAt` / 以及（对非 user 消息）`prepareMessageContext`。

通过全仓排查，产生 `type=error` / `session.error` 事件的路径共 7 条，其中 6 条已正确调用 enrich，但：

- **`InboundProcessingService.handleAgentOffline`**（`InboundProcessingService.java:335-340`）：agent 离线时直接 `StreamMessage.builder().type(ERROR).error(...).build()` 后交给 `outboundDeliveryDispatcher.deliver(...)`，**跳过** `enrichStreamMessage`。下游 `ExternalWsDeliveryStrategy.deliver` 本身不做字段兜底，直接 `objectMapper.writeValueAsString(msg)` —— 于是序列化出的 JSON 缺失 `welinkSessionId`。

### 1.3 结构性隐患（bug 之上）

仅修这一处可消除眼前问题，但根因是 **架构上依赖"每个发射点手动调 enrich"的隐式约定**。任何未来新增的 `StreamMessage.builder().type(...)...` 调用点都可能再次漏配；`StreamMessage.error(...)` / `StreamMessage.sessionStatus(...)` 等静态工厂方法也不会自动填 `welinkSessionId`。

本设计目标：**消除这个隐式约定**，让出站路径在结构上不可能再漏字段。

---

## 2. 设计决策

### 2.1 方案选型：提取统一出站门面

在 `service.delivery` 包下新增 `StreamMessageEmitter`，作为所有 StreamMessage 出站的唯一权威入口。其职责：

1. 统一执行 enrich（字段填充 + 幂等的 messageContext 分配）
2. 按语义选择出口：业务投递 / 前端强推 / 前端强推+buffer

### 2.2 出站矩阵（现状梳理，作为设计基准）

skill-server 有三种语义不同的出站路径：

| 语义 | 目标下游 | 当前入口 | 特征 |
|------|---------|---------|------|
| 业务投递 | 按 session domain 路由：miniapp / IM REST / External WS | `OutboundDeliveryDispatcher.deliver` | 命中策略链 |
| 前端强推 | 只给 miniapp 前端（通过 `user-stream:{userId}` Redis channel） | `GatewayMessageRouter.broadcastStreamMessage` | 绕过 dispatcher，强制 miniapp 路径（用于跨 session 控制消息） |
| 前端强推 + 回放 | 前端 + 加入 buffer 用于断线重连 | `GatewayMessageRouter.publishProtocolMessage` | broadcast + `bufferService.accumulate` |

本次重构 **不合并这三种语义**，它们本就服务不同用途；Emitter 提供三个明示意图的方法分别对应。

### 2.3 API 形态

```java
package com.opencode.cui.skill.service.delivery;

public class StreamMessageEmitter {

    /** 业务投递：按 session domain 路由到 miniapp/IM/ExternalWs 策略。
     *  取代原 "enrichStreamMessage + dispatcher.deliver" 组合。 */
    public void emitToSession(SkillSession session, String sessionId,
                              String userId, StreamMessage msg);

    /** 前端强推：直接推给 miniapp 用户（绕过 session domain 路由）。
     *  取代原 broadcastStreamMessage。
     *  userIdHint 为 null 时内部反查。 */
    public void emitToClient(String sessionId, String userIdHint, StreamMessage msg);

    /** emitToClient + bufferService.accumulate（控制类协议消息需要回放时用）。
     *  取代原 publishProtocolMessage。 */
    public void emitToClientWithBuffer(String sessionId, StreamMessage msg);
}
```

设计考量：
- **三方法而非 enum 单方法**：三种语义的参数形参要求不同（`emitToSession` 要 session，`emitToClient` 不要），分方法让类型系统挡住误用
- **保留 `userIdHint`**：多数 handler 上下文里 userId 已在手，免去反查；null 时触发 fallback
- **放在 `service.delivery` 包**：与 dispatcher 同栖，出站聚合点

### 2.4 enrich 语义

```java
private void enrich(String sessionId, StreamMessage msg) {
    if (msg == null || sessionId == null) return;

    msg.setSessionId(sessionId);           // 内部字段，始终覆盖
    msg.setWelinkSessionId(sessionId);      // 协议字段，始终覆盖（canonical 语义）

    // emittedAt —— 按白名单 + only-if-blank（与原逻辑一致）
    if (!EMITTED_AT_EXCLUDED_TYPES.contains(msg.getType())
            && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
        msg.setEmittedAt(Instant.now().toString());
    }

    // messageContext 分配（仅 assistant 侧消息触发；tracker 内部幂等）
    if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            persistenceService.prepareMessageContext(numericId, msg);
        }
    }
}
```

**`welinkSessionId` 保持 canonical overwrite**：当前协议语义下 `welinkSessionId` 是内部会话 ID 的权威表达（即 `SkillSession.id`），全仓搜索未发现任何"显式传异值"的调用场景（`StreamMessage.userMessage` 在 `SkillMessageController:131` 传入的值与上下文 `sessionId` 同一变量）。本次重构仅做"统一出口"，**不调整** welinkSessionId 的覆盖语义——避免把"sessionId ≠ welinkSessionId 合法"写成被测试保护的契约。如果未来要放宽为 caller-overridable 字段，需独立协议变更说明 + 跨端验证，不走本 PR。

**幂等性**：setter 对相同入参稳定；`prepareMessageContext` 内部通过 `tracker.resolveActiveMessage` 幂等（该方法在 `persistIfFinal` 中本就多次调用）。

### 2.5 错误处理契约

| 方法 | 异常行为 | 理由 |
|------|---------|------|
| `emitToSession` | **emitter 层不引入额外 try-catch**；异常策略由下游 strategy 自行决定（当前 `MiniappDeliveryStrategy` / `ImRestDeliveryStrategy` / `ExternalWsDeliveryStrategy` 内部均 best-effort 吞异常） | 与现状一致；emitter 不承诺"handler 可感知投递失败"这种强合约——strategy 层已吞，承诺了也做不到；strategy 层异常策略改造不在本次范围 |
| `emitToClient` | **try-catch 吞并**，仅 log.error | 与原 `broadcastStreamMessage` 行为一致；广播失败不应中断调用链 |
| `emitToClientWithBuffer` | `emitToClient` 已吞异常 → `bufferService.accumulate` 无条件执行 | 沿袭原 `publishProtocolMessage` 既成语义，不改变 |

---

## 3. 迁移映射表

共 13 处生产代码调用点 + 5 处测试断言。

### 3.1 业务投递：`enrich + dispatcher.deliver` → `emitToSession`

| # | 位置 | 场景 | 风险 |
|---|------|------|------|
| 1 | `GatewayMessageRouter.java:547` | `dispatchStreamMessage` 主流程 | 低 |
| 2 | `GatewayMessageRouter.java:584` | flush 累积 text.delta → text.done | 低 |
| 3 | `GatewayMessageRouter.java:593` | sessionStatus("idle") | 低 |
| 4 | `GatewayMessageRouter.java:644` | handleErrorEvent 的 error | 低 |
| 5 | `GatewayMessageRouter.java:788` | permission 分发 | 低 |
| 6 | `GatewayMessageRouter.java:1057` | context overflow 重置 | 低 |
| **7** | **`InboundProcessingService.java:339`** | **agent offline 到 external ws（当前 bug 点）** | **中** — 需新注入 emitter |

### 3.2 前端强推：`broadcastStreamMessage` → `emitToClient`

| # | 位置 | 场景 | 风险 |
|---|------|------|------|
| 8 | `GatewayMessageRouter.java:471` | activateSession 后 "busy" | 低 |
| 9 | `GatewayMessageRouter.java:672` | agent.online 广播 | 低 |
| 10 | `GatewayMessageRouter.java:687` | agent.offline 广播 | 低 |
| 11 | `GatewayMessageRouter.java:759` | retryPendingMessages 后 "busy" | 低 |
| 12 | `SkillMessageController.java:131` | 用户消息多端同步广播 | 低 |
| 13 | `GatewayMessageRouter.java:1127` (`RebuildCallback` 实现) | rebuild broadcast | 低 |
| 14 | `GatewayRelayService.java:339` (`RebuildCallback` 实现) | 同上另一份实现 | 低 |

### 3.3 前端强推 + 缓冲：`publishProtocolMessage` → `emitToClientWithBuffer`

| # | 位置 | 场景 | 风险 |
|---|------|------|------|
| 15 | `SkillMessageController.java:173` | agent offline error（REST 入口） | 低 |
| 16 | `SkillMessageController.java:423` | permission reply | 低 |
| 17 | `InboundProcessingService.java:281` | IM 入站 reply 广播 | 低 |
| 18 | `GatewayRelayService.java:324-326` (public API `publishProtocolMessage`) | 外部 API | **保留方法签名，内部 delegate 到 emitter** |

### 3.4 删除与改造

| 动作 | 对象 | 说明 |
|------|------|------|
| 删除 | `GatewayMessageRouter.enrichStreamMessage` | 逻辑整体搬到 `StreamMessageEmitter.enrich` |
| 改造（保留签名）| `GatewayMessageRouter.broadcastStreamMessage` | 改为 `emitter.emitToClient(...)` 的 thin wrapper |
| 改造（保留签名）| `GatewayMessageRouter.publishProtocolMessage` | 改为 `emitter.emitToClientWithBuffer(...)` 的 thin wrapper |
| 保留 | `GatewayRelayService.publishProtocolMessage` | 外部 API，签名不动，内部 delegate |
| 保留 | `MiniappDeliveryStrategy` | 不动；作为"miniapp session 的业务投递策略"仍需 |

**API 稳定承诺**：`GatewayRelayService.publishProtocolMessage`、`GatewayMessageRouter.broadcastStreamMessage`、`GatewayMessageRouter.publishProtocolMessage` 三个 public 方法保留签名（thin wrapper），以避免破坏现有测试和其他 service 引用。API 清理留作后续独立 PR。

### 3.5 日志前缀改动

`broadcastStreamMessage` 内原 `[EXIT->FE] Broadcast StreamMessage: ...` 改为 `[EMIT->CLIENT] ...`（同义但前缀统一）。如果线上观测有基于原前缀的 grep 规则，需同步更新。

---

## 4. 测试策略

### 4.1 新增：`StreamMessageEmitterTest`（19 条）

位置：`skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

Mock：`OutboundDeliveryDispatcher`、`RedisMessageBroker`、`BufferService`、`MessagePersistenceService`、`SkillSessionService`。`ObjectMapper` 用真实实例。

#### enrich 语义（9 条，enrich-1 至 enrich-9）

| 用例 | 断言 |
|------|------|
| enrich-1 | welinkSessionId 已预填任意值 → 被 sessionId 参数**覆盖**（canonical 语义守护） |
| enrich-2 | welinkSessionId null → 被 sessionId 填充 |
| enrich-3 | emittedAt 在白名单类型 → 保持 null |
| enrich-4 | emittedAt 非白名单类型 + null → 被填当前时间 |
| enrich-5 | emittedAt 已预填 → 保持原值 |
| enrich-6 | role="user" → prepareMessageContext 零交互 |
| enrich-7 | role="assistant" + 数字 sessionId → prepareMessageContext 被调 1 次 |
| enrich-8 | role="assistant" + 非数字 sessionId → prepareMessageContext 零交互 |
| enrich-9 | 连续两次 emit 同一 msg → 字段稳定；emittedAt 不被第二次覆盖 |

#### emitToSession（2 条 + 1 契约）

| 用例 | 断言 |
|------|------|
| session-1 | dispatcher.deliver 调用 1 次 |
| session-2 | redisBroker.publishToUser 零交互 |
| session-3 | dispatcher 抛 RuntimeException → emitToSession 不新增吞异常逻辑（异常冒到调用方；不是合约承诺，仅行为断言） |

#### emitToClient（4 条）

| 用例 | 断言 |
|------|------|
| client-1 | userIdHint 非 null → publishToUser 1 次，envelope 含 sessionId/userId/message |
| client-2 | hint==null + session 能查到 → 用 session.getUserId() 发布 |
| client-3 | hint==null + session 查不到 → publishToUser 零交互，仅 warn |
| client-4 | publishToUser 抛异常 → 不对外抛，log.error |

#### emitToClientWithBuffer（2 条）

| 用例 | 断言 |
|------|------|
| buffer-1 | 正常场景 → publishToUser + bufferService.accumulate 各 1 次 |
| buffer-2 | publishToUser 抛异常 → bufferService.accumulate **仍执行 1 次**（沿袭原语义） |

#### 边界（1 条）

| 用例 | 断言 |
|------|------|
| boundary-1 | sessionId==null 或 msg==null → 三方法均 no-op，所有依赖零交互 |

### 4.2 回归测试：agent-offline 调用路径

位置：`InboundProcessingServiceTest`（已有文件追加）

**职责边界**：这条测试只验证"`handleAgentOffline` 正确走了 emitter 路径"——即从原来"直接 `dispatcher.deliver`，跳过 enrich"改为"走 `emitter.emitToSession`，由 emitter 完成 enrich"。由于 emitter 是 mock，captor 捕获的是 enrich **之前** 的 msg，断言 welinkSessionId 的值毫无意义；那部分语义由 §4.1 emitter 单测 + §4.3 序列化守护覆盖。

```java
@Test
void handleAgentOffline_ExternalWs_shouldRouteViaEmitter() {
    SkillSession session = mockExternalWsSession(id=101L, businessDomain="ext");
    when(sessionManager.findSession(...)).thenReturn(session);
    // 触发 agent offline

    inboundProcessingService.process(...);

    // 只验证调用路径与 msg 语义字段（非 enrich 填充字段）
    ArgumentCaptor<StreamMessage> cap = ArgumentCaptor.forClass(StreamMessage.class);
    verify(emitter).emitToSession(eq(session), eq("101"), isNull(), cap.capture());
    assertEquals(StreamMessage.Types.ERROR, cap.getValue().getType());
    assertEquals(AGENT_OFFLINE_MESSAGE, cap.getValue().getError());
}
```

> **关于旧路径消失的验证**：`InboundProcessingService` 中 `outboundDeliveryDispatcher` 仅在 `handleAgentOffline` 一处使用；迁移后该依赖预期从类的字段、构造器参数、测试 mock 中一同移除。因此**不通过测试断言**（如 `verifyNoInteractions(outboundDeliveryDispatcher)`——那样会引用一个不存在的 mock）来守护旧路径消失，而是由 §5.2b 的 `grep -r "outboundDeliveryDispatcher\.deliver" skill-server/src/main` 仅剩 1 处（emitter 内部）的 checklist + code diff 审阅来保证。

**Bug 闭环的三层分工**：
- §4.1 `StreamMessageEmitterTest` — 守 enrich 语义（welinkSessionId 被填）
- §4.2 `InboundProcessingServiceTest`（本节）— 守调用路径（不再绕过 emitter）
- §4.3 `ExternalWsDeliveryStrategyTest` — 守 JSON 出口（序列化后字段仍在）

三者叠加，才是完整的回归防线。

### 4.3 序列化守护：`ExternalWsDeliveryStrategyTest` 追加 1 条

原 bug 的**真实出口**是 `ExternalWsDeliveryStrategy.deliver` 的 `objectMapper.writeValueAsString(msg)`——单测层面只校验 msg 对象字段不足以守护 JSON 序列化的产物（`@JsonIgnore` / getter / 嵌套 `@JsonUnwrapped` 任一偏差都可能让 `welinkSessionId` 再次丢失）。

位置：`skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java`（已存在）

```java
@Test
void deliver_errorEvent_serializedJsonContainsWelinkSessionId() {
    // given: 一条 enrich 过的 error StreamMessage
    SkillSession session = mockExternalWsSession(id=101L);
    StreamMessage msg = StreamMessage.builder()
            .type(StreamMessage.Types.ERROR)
            .error("agent offline")
            .build();
    msg.setSessionId("101");
    msg.setWelinkSessionId("101");   // 模拟 emitter 已 enrich 的态

    when(externalStreamHandler.pushToOne(anyString(), anyString())).thenReturn(true);
    ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);

    // when
    strategy.deliver(session, "101", null, msg);

    // then: 捕获实际发出的 JSON payload，断言含 welinkSessionId
    verify(externalStreamHandler).pushToOne(anyString(), jsonCap.capture());
    JsonNode payload = new ObjectMapper().readTree(jsonCap.getValue());
    assertEquals("101", payload.path("welinkSessionId").asText());
    assertEquals("error", payload.path("type").asText());
}
```

这条测试直接贴近 bug 出口——将来任何改动（Jackson 注解、getter 行为、strategy 的 wrapper 逻辑）只要让 `welinkSessionId` 从 JSON 里掉出去，就会在 CI 被抓。

### 4.4 现有测试更新

| 测试 | 改动 |
|------|------|
| `SkillSessionControllerTest:127,167` | 断言不变（外部 API 签名保留） |
| `SkillMessageControllerTest:260,394` | 断言不变 |
| `InboundProcessingServiceTest:204` | 断言不变 |
| `SessionRebuildServiceTest` | 断言不变（RebuildCallback 接口不动） |

`InboundProcessingServiceTest` 构造函数需新增 `StreamMessageEmitter` mock 依赖。

### 4.5 不做：新增跨组件集成测试

理由：Emitter 是纯内部组装层；§4.3 的序列化守护测试已在 `ExternalWsDeliveryStrategyTest` 层抓住 bug 真实出口；`tools/e2e-test-field-consistency.py` 作为 ship 前人工验收覆盖端到端。跨组件 SpringBootTest 成本高收益低。

---

## 5. 实施顺序

### Step 1 — 引入 Emitter（独立，零迁移）

- 新建 `StreamMessageEmitter.java`
- 新建 `StreamMessageEmitterTest.java`（19 条）

验收：
```
mvn -pl skill-server test -Dtest=StreamMessageEmitterTest
```

此时 Router 代码未改动，行为零变化。独立可验证、可回滚。

### Step 2 — 全量迁移（18 处 + 清理，单 commit）

批次：

| 批次 | 修改 |
|------|------|
| 2a | Router 内 10 处（业务 6 + 前端 4）改走 emitter |
| 2b | 删除 `Router.enrichStreamMessage`；`broadcastStreamMessage` / `publishProtocolMessage` 改 thin wrapper |
| 2c | `SessionRebuildService` 的两个 `RebuildCallback` 实现体改调 emitter |
| 2d | `InboundProcessingService:281, :339` 改调 emitter；追加 agent-offline 回归用例 |
| 2e | `SkillMessageController:131, :173, :423` 改调 emitter |
| 2f | 清理 Router 未使用的 import、`EMITTED_AT_EXCLUDED_TYPES` 搬到 emitter |

**单 commit 完成**。碎片化 commit 会产生"引用悬空"的中间态，不利于 revert。

验收：
- `mvn -pl skill-server test` 全量全绿
- `grep -r "enrichStreamMessage" skill-server/src` 零结果
- `grep -r "outboundDeliveryDispatcher\.deliver" skill-server/src/main` 仅 1 处（emitter 内）
- `grep -r "redisMessageBroker\.publishToUser" skill-server/src/main` 仅 2 处（emitter + MiniappDeliveryStrategy）

### Step 3 — 手工/E2E 验收

- 本地启 skill-server + mock external ws client
- 触发 agent offline 场景，抓 external ws 收到的 JSON，断言含 `welinkSessionId`
- 运行 `python tools/e2e-test-field-consistency.py` 通过
- 日志前缀 `[EMIT->CLIENT]` 正常出现

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| `prepareMessageContext` 调用路径从"仅 enrich 点"扩大到"每次 emit"，引入非幂等副作用 | 低 | 中 | 已确认 `tracker.resolveActiveMessage` 是"查或取"语义；`persistIfFinal` 已多次调用该方法；enrich-9 用例守护 |
| 外部 API wrapper 行为差异破坏 controller 测试 | 中 | 低 | 外部 API 签名不变，controller 层 mock 不受影响；内部 delegate 由 Step 2 全量测试覆盖 |
| `emitToClientWithBuffer` buffer 语义偏差 | 低 | 中 | buffer-1/2 直接守护；沿袭原"无条件 buffer"不变化 |
| 日志前缀改动影响线上 grep 告警 | 中 | 低 | PR 描述必须列出；观测团队同步更新查询 |
| 未来 Jackson 序列化 / getter 行为 / strategy 包装偏差导致再次漏字段（bug 出口盲区） | 低 | 高 | §4.3 新增 `ExternalWsDeliveryStrategyTest` 序列化快照用例，在 bug 真实出口处守护 JSON payload 含 `welinkSessionId` |

---

## 7. 回滚

| 阶段 | 回滚 |
|------|------|
| Step 1 | 删新文件，零依赖 |
| Step 2 | `git revert <step2_commit>`（单 commit，原子） |
| Step 3 | 无代码改动，仅观测 |

不推荐将 Step 2 拆多 commit —— 中间态含引用悬空的 `enrichStreamMessage`，扩大回滚面。

---

## 8. 合并前 Checklist

- [ ] Step 1 单测全绿
- [ ] Step 2 全量 `mvn -pl skill-server test` 全绿
- [ ] `grep -r "enrichStreamMessage" skill-server/src` 为空
- [ ] `grep -r "outboundDeliveryDispatcher\.deliver" skill-server/src/main` 仅 1 处
- [ ] `grep -r "redisMessageBroker\.publishToUser" skill-server/src/main` 仅 2 处
- [ ] 新增 19 条 emitter 用例 + 1 条 agent-offline 回归 + 1 条 ExternalWs 序列化守护
- [ ] `tools/e2e-test-field-consistency.py` 本地通过
- [ ] 手工验 agent offline：external ws JSON 含 `welinkSessionId`
- [ ] PR 描述含"`[EXIT->FE]` → `[EMIT->CLIENT]`"日志变更说明

---

## 9. 范围外（Out of Scope）

显式不做的事：

- **不引入 feature flag**：重构不改外部行为，灰度开关徒增代码路径
- **不改 ai-gateway**：本次只在 skill-server 内部
- **不改 miniapp 前端**：协议字段无增减
- **不清理外部 API wrapper**：`GatewayRelayService.publishProtocolMessage` / `Router.broadcastStreamMessage` / `Router.publishProtocolMessage` 保留；API 收敛留作后续独立 PR
- **不合并三种出站语义**：emitToSession / emitToClient / emitToClientWithBuffer 服务不同用途，方法分离是设计意图
- **不放宽 `welinkSessionId` 覆盖语义**：保持现有"canonical overwrite"；若未来需要 caller-overridable，独立协议变更 PR 处理
- **不改造 strategy 层异常策略**：本次 emitter 不引入额外 try-catch，strategy 层保持 best-effort

---

## 10. Revisions

- **v1**（初稿）
- **v2**（Codex 静态审阅后修订）：
  - 撤回 `welinkSessionId` only-if-blank 改动，保持 canonical overwrite（§2.4）。理由：当前调用链不存在显式传异值场景，原稿改动属范围蔓延 + 协议语义变更
  - 降低 `emitToSession` 异常契约措辞（§2.5）：从"让 handler 感知投递失败"改为"emitter 不引入额外 try-catch；strategy 层保持 best-effort"；session-3 用例从"契约"降为"行为断言"
  - 新增 §4.3 `ExternalWsDeliveryStrategyTest` 序列化守护用例，覆盖 bug 真实出口（JSON payload 含 `welinkSessionId`）
  - 同步更新迁移表风险级别（§3.2 #12）、风险表（§6）、范围外清单（§9）、checklist（§8）
- **v3**（Codex 二次审阅后修订）：
  - 修正 §4.2 回归测试设计错误：emitter 为 mock 时，captor 捕获的是 enrich 之前的 msg，`assertEquals("101", ...getWelinkSessionId())` 必然失败。改为只验证调用路径（`verify(emitter).emitToSession(...)` + msg 语义字段 + `verifyNoInteractions(dispatcher)`）；welinkSessionId 填充验证由 §4.1 负责，JSON 出口验证由 §4.3 负责——三层测试各司其职
- **v4**（Codex 三次审阅后修订）：
  - 从 §4.2 测试代码中移除 `verifyNoInteractions(outboundDeliveryDispatcher)`。事实核实：`InboundProcessingService` 中 `dispatcher` 仅在 `handleAgentOffline` 一处使用，迁移后整个依赖（字段 + 构造参数 + 测试 mock）会一并删除；保留该断言会引用一个不存在的 mock 导致编译失败。改为用 checklist 的 grep 规则 + code diff 审阅来保证旧路径消失
