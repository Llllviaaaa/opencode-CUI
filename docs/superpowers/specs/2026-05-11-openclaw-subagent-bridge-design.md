# OpenClaw Subagent 接入 message-bridge 设计

> **Date**: 2026-05-11
> **Type**: Cross-layer (message-bridge-openclaw + ai-gateway + skill-server + skill-miniapp)
> **Status**: 调研完成 + Codex (gpt-5.5 xhigh) 评审修订 v2.1，待评审

---

## 一句话目标

让 openclaw 主对话派生出来的子任务，能在小程序里像主对话一样**逐字流式**显示，UI 渲染成独立的 SubtaskBlock 卡片，permission 询问也能闭环。

---

## 背景：openclaw 的 subagent 是啥

主对话碰到累活（比如"帮我审这段代码"），会调一个内部工具叫 `sessions_spawn`，自己开一个**新的子会话窗口**，让另一个 AI 在里面专门干。

- 子窗口跟主对话**完全独立**：自己的会话 ID、自己的对话记录、自己的模型、自己的工作目录
- 子窗口 ID 长这样：`agent:<agentId>:subagent:<uuid>`
- 干完了，子窗口的结果**回注**到主对话（openclaw 内部会把结果合成一个内部事件让主 AI 接着说）
- 父子关系在子窗口的会话条目里靠 `spawnedBy` 字段记录
- 子任务可被父对话用 `subagents` 工具控制（list / kill / steer）
- **可以嵌套**：子任务又能开自己的子任务，由 `spawnDepth` / `maxSpawnDepth` 控制层数，cascade kill 串起来

我们之前对接的 opencode 也有类似机制（Task 工具派生子会话）。

---

## 当前现状

### 我们 bridge 插件能做啥

下行 6 个动作（ai-gateway → bridge → openclaw）：
- `chat` / `create_session` / `close_session` / `abort_session`
- `permission_reply` / `question_reply`

上行：bridge 订阅 openclaw 的 agent 事件流（`runtime.events.onAgentEvent`），把流式输出转换成 opencode 形态的 part 事件，回传 ai-gateway。

### 不能做啥

**bridge 完全感知不到子任务的存在**：
- bridge 视角里 openclaw 就是"一个用户、一个会话窗口"，没有父子层级
- 子任务里 AI 输出的文字、要权限、问问题 —— bridge 收到的事件都被默认丢掉

### 顺带发现的现存 bug（跟 subagent 没关系，但要一起修）

bridge 现在的"权限请求"和"用户提问"处理代码挂在一个**死分支**里：

代码在 `OpenClawGatewayBridge.ts:1917-2044` 的 `subscribeRuntimeGatewayEvents`，它探测 `runtime.events.onGatewayEvent / onSystemEvent / onEvent` 三个方法之一。但 openclaw 公开 SDK 只有 `onAgentEvent` 和 `onSessionTranscriptUpdate` 两个 events 订阅口（参考 `plugins/runtime/types-core.ts:287`），那三个方法根本不存在。

**结论**：bridge 上行的 permission 处理在生产环境**实际从未触发过**。

---

## 五个关键发现（已逐项验证，含 Codex 评审复核）

### 1. 子任务事件 bridge 能收到

openclaw 用同一条 `onAgentEvent` 流发出所有 agent 事件，包括子任务的。bridge 已经订阅了这条流。

### 2. 子任务事件**带 sessionKey**

openclaw 在 emit agent event 时，会按"任务上下文表"里的 `isControlUiVisible` 决定是否把 sessionKey 擦掉（避免泄漏到外部渠道）。

- **主对话**：bridge 调 `dispatchReplyFromConfig` 启动的 run，走 reply runner 路径（auto-reply 队列），注册 context 时显式标 `isControlUiVisible: false`（因为渠道是 message-bridge 不是 webchat），sessionKey **被擦**
- **子任务**：`sessions_spawn` 调 gateway `agent` 方法启动的 run，走 `agent-command.ts` 路径，注册 context 时**没传** `isControlUiVisible`，默认 `true`，sessionKey **保留**

**子任务调用链**（Codex 验证）：
```
sessions_spawn → spawnSubagentDirect → callGateway("agent")
              → agentCommandFromIngress → agent-command.ts
              → command/attempt-execution → runEmbeddedPiAgent
```

> ⚠️ **澄清**：子任务**仍走 `runEmbeddedPiAgent`**（agent 执行引擎），但**不走 `agent-runner-execution.ts`**（auto-reply 队列路径）。两者不要混为一谈。

源码证据：
- `agent-command.ts:626-630` —— 子任务路径注册，未传 isControlUiVisible
- `infra/agent-events.ts:217-227` —— `isControlUiVisible = context?.isControlUiVisible ?? true`
- `auto-reply/reply/agent-runner-execution.ts:1148` —— 整个源码里唯一一处显式 set false 的地方，跟子任务路径不相干

任务上下文表的注册（`agent-command.ts:626`）在所有 emit（`agent-command.ts:1087+`）之前，没有竞态。

### 3. 子任务的父对话可反查（spawnedBy）

子任务的会话条目里有 `spawnedBy = 父 sessionKey`，写入时机在 `subagent-spawn.ts:1053-1068`，**早于** `callGateway("agent")` 触发任何 agent event。bridge 用公开 SDK `runtime.agent.session.loadSessionStore(...)` 能读到。

> ⚠️ **注意**：`SubagentRunRecord` 注册表（`registerSubagentRun`）的写入在 `callGateway("agent")` 返回后才执行，所以早期事件可能先于 registry 到。**bridge 不应依赖 registry**，要直接走 sessionStore 的 `spawnedBy`，这条够用。

### 4. permission 信号来自 onAgentEvent

子任务（和主对话）的权限请求实际通过 `onAgentEvent` 发出来：
- `stream === "approval"`
- 顶层 **`evt.sessionKey` 是会话标识来源**
- `data` 形如 `{phase: "requested"|"resolved", kind, status, approvalId, command, ...}`
- **重要**：`data` **不带** `sessionID/sessionId` 字段，session 关联**只能用顶层 evt.sessionKey**

源码：`infra/agent-events.ts:263` `emitAgentApprovalEvent`、`agents/pi-embedded-subscribe.handlers.tools.ts:1069`。

bridge 当前 `handleRuntimeAgentEvent` 完全忽略 `stream === "approval"`，需要新增分支处理（这也是修死代码 bug 的正确位置）。

### 5. question 在 openclaw 里**不存在**

整个 openclaw 源码里没有"问用户问题"机制：
- 没有 `question.asked` 事件
- 没有 `runtime.question.reply` API
- 没有任何相关工具

bridge 代码里 `buildQuestionAskedEvent` / `runtime.question.reply` 是对着空气写的（疑似从 opencode 协议照搬）。

**结论**：openclaw subagent 不会问用户问题。这条链路不用做。

---

## 设计方案

### 总体路线：被动监听，bridge 做透传 + 标注

让主对话内的模型自己决定何时调 `sessions_spawn`，openclaw 自管子任务生命周期、announce 回注父对话、kill/steer 工具，bridge **只做事件透传 + 标注 subagent 字段**。

不走"bridge 主动起子任务"的路线，原因：
- 走 `runtime.subagent.run` 自起子 run 不会进 `SubagentRunRecord` 注册表，丢失 announce flow 和 control 工具能力
- 重建一遍 openclaw 内部能力的代价远大于透传

### 数据流

```
父对话 model 决定派生
  ↓
sessions_spawn 工具 → callGateway("agent", {sessionKey: childSessionKey, ...})
  ↓ (sessionStore.spawnedBy 此时已写入)
agent-command.ts 启动子 run → registerAgentRunContext({sessionKey: childSessionKey})
  ↓
emitAgentEvent({runId, sessionKey: childSessionKey, stream, data})
  ↓
bridge.handleRuntimeAgentEvent 收到事件
  ├─ evt.sessionKey 命中 bridge 的活跃父会话表 → 主对话事件（按现状走）
  └─ evt.sessionKey 不在活跃父会话表，但是 subagent 格式 → 走子任务分支：
        1. 查 byChildSessionKey 缓存
        2. miss → loadSessionStore 拿 spawnedBy
        3. 反查父 toolSessionId（顺 parent 链一路爬到本 bridge 已知的父）
        4. 在 byChildSessionKey 表里登记 child→parent (含 depth)
        5. 合成上行 tool_event，注入 subagentSessionId/subagentName/depth
  ↓
ai-gateway 透传（已支持 subagentSessionId/subagentName，depth 需新增）
  ↓
skill-server 持久化为带 subagent 字段的 part
  ↓
miniapp 按 subagentSessionId 分组渲染 SubtaskBlock
```

### 三个 Codex 评审挑出的关键设计点

#### A. 关闭/中止**不要级联删子任务**（重要修正）

openclaw 自己已经在 subagent lifecycle cleanup 里按 `cleanup: "delete"|"keep"` 策略清理，`subagents kill` 工具也会 cascade 杀子孙。**bridge 再去主动 delete child session 会**：
- 双删 → 失败回滚
- 抢在最终 `subagent_announce` 之前删 → 父对话拿不到子任务结果

正确做法：
- `close_session`：bridge **只删自己的 toolSessionId 映射**，不动 openclaw 任何 session。让 openclaw 的 cleanup 收尾。
- `abort_session`：如果 ai-gateway 真要主动中止子任务，bridge 调 `subagents` 工具 kill 或 gateway `sessions.abort`，但**不要 delete**。
- 父任务 close/abort 时如何处理还在跑的子任务 → 让 openclaw 的 cascade 机制决定，bridge 只做映射清理。

#### B. announce 回注不用特殊识别

子任务结束时 openclaw 调 `runSubagentAnnounceFlow`，把子结果合成一个 `task_completion` internalEvent **注入回父 session**，触发父 session 上一次新的 agent run。

**结论**：bridge **不需要识别这次 run 是 announce 回注**，让它走普通主对话流即可。

理由：UX 上是合理的 ChatGPT 风格 Tools 体验——
- 子任务跑的时候 → 子任务卡片（SubtaskBlock）逐字显示子任务自己的产出
- 子任务结束 → 卡片停在"完成"
- announce 回注触发的父 run → 主对话气泡冒出"我审完了，问题是 1, 2, 3..."

> 已源码验证：`inputProvenance` **不会**经 `onAgentEvent` 透出（`infra/agent-events.ts` 里没有它，`server-methods/agent.ts:418-420` 仅用于内部决策）。所以"看 inputProvenance 识别"这条路本来也走不通。简化方案反而更干净。

#### C. abort 状态机有 grace 期

openclaw 对子任务 abort 不是简单的"end"事件，有完整状态机：
- `lifecycle:end` with `aborted: true` → 进入 15 秒 grace 期
- 15 秒内没拿到结果 → 重判为 `timeout`
- 还有独立的 `subagent-killed` 路径（被 kill 工具主动杀的）
- `outcome.status`: `ok | error | timeout | killed`

bridge 处理 lifecycle 时不能粗暴地"end 就完事"，要：
- 看 `aborted` / `error` / `outcome.status` 一起决定子任务在 UI 上的最终态
- 处理 grace 期内可能到的 late events（见 §D）

#### D. late events / 子会话已关后还来事件

子任务 session 已 close/abort 后，openclaw 内部仍可能有 lifecycle/approval/tool 事件晚到（agent 正在收尾、模型 stream 还没全部 drain）。

应对：
- bridge 的 `terminatedSessionKeys` 黑名单要覆盖到子会话
- 收到黑名单子会话事件 → 静默丢弃，但记日志便于排查

#### E. 嵌套 subagent

子任务能再开子任务（最多到 `maxSpawnDepth`，默认有限）。意味着：
- `byChildSessionKey` 表里要存 `depth` 和 `chain`（child → ... → 主 bridge 父对话）
- 上行事件 payload 要带 `depth`，让小程序决定怎么排版（折叠 / 缩进 / 仅显示最深层）
- cascade kill：父 toolSessionId 一旦 close/abort，bridge 要把所有 descendant 子任务的映射都清

### 分层改造

#### bridge 层（改动最多）

| 改动点 | 文件 |
|---|---|
| `SessionRegistry` 增 `byChildSessionKey` 表（含 parentToolSessionId / depth / chain） | `session/SessionRegistry.ts` |
| `handleRuntimeAgentEvent` 增"未知 sessionKey 探测 + sessionStore 反查 spawnedBy" | `OpenClawGatewayBridge.ts:2083+` |
| `handleRuntimeAgentEvent` 增 `stream==="approval"` 分支（同时迁移 permission 现存死代码） | 同上 |
| `handleRuntimeAgentEvent` 增 `stream==="lifecycle"` 透传（start/end/error/timeout/aborted） | 同上 |
| `handleRuntimeAgentEvent` 增 announce 回注识别（看 inputProvenance / sourceTool） | 同上 |
| 拆掉死代码 `subscribeRuntimeGatewayEvents` | `OpenClawGatewayBridge.ts:1917+` |
| 上行事件 builder 携带 `subagentSessionId / subagentName / depth` | `session/upstreamEvents.ts` |
| 下行 schema 加可选 `subagentSessionId`：`chat / permission_reply / abort_session / close_session` | `contracts/downstream.ts` + `protocol/downstream.ts` |
| 下行带 `subagentSessionId` 时路由到子会话（chat 用 `runtime.subagent.run({sessionKey: childSessionKey})`、permission 走子 sessionKey）；**close 只删 bridge 映射，不删 openclaw session**；abort 走 `gateway.request("sessions.abort", {key: childSessionKey})` | `OpenClawGatewayBridge.ts` chat/permission/close/abort handlers |
| 处理 late events：terminatedSessionKeys 扩展到子会话 | `OpenClawGatewayBridge.ts` |
| 移除 question 相关代码（buildQuestionAskedEvent / runtime.question.reply / handleQuestionReply） | 多处 |

#### ai-gateway 层（轻）

| 改动点 | 状态 |
|---|---|
| `GatewayMessage` 已有 `subagentSessionId/subagentName` | 已就绪 |
| `GatewayMessage` 加 `subagentDepth` 字段 | **要补** |
| `GatewayMessageRouter` 已支持透传 | 已就绪（depth 需补） |
| 下行 JSON 注入 `subagentSessionId` 给 plugin | **要补** |

#### skill-server 层（轻）

| 改动点 | 状态 |
|---|---|
| DB 已有 `subagent_session_id / subagent_name`（V8 migration） | 已就绪 |
| DB 加 `subagent_depth` / `parent_subagent_session_id`（用于嵌套渲染） | **要补**（V9 migration） |
| `SkillMessageController` q_r/p_r 路由已支持 | 已就绪 |
| `InboundProcessingService` part 注入 subagent 字段已支持 | 已就绪 |
| 处理 `subagent.lifecycle` 事件（start/end/timeout/killed） | **要补** |
| `close_session / abort_session` 处理：**只清自己侧的映射，不要再下行 delete 子任务** | **要改** |

#### miniapp 层（少量）

| 改动点 | 状态 |
|---|---|
| `useSkillStream` 按 `subagentSessionId` 分组 SubtaskBlock | 已就绪 |
| `SubtaskBlock` 组件 | 已就绪 |
| `SubtaskBlock` 处理 `depth` 嵌套渲染（缩进 / 折叠） | **要补** |
| `SubtaskBlock` 处理 lifecycle 各种终态（running/done/timeout/killed/error） | **要补** |
| announce 回注的展示：作为 SubtaskBlock 的"结果回传"段，不另起气泡 | **要补** |
| `replyPermission` 携带 `subagentSessionId` | 已就绪 |

#### DB 层

V9 migration：加 `subagent_depth` 和 `parent_subagent_session_id`。

---

## 工作量重估（v2，参考 Codex 评审）

| 工作 | 估算 | 风险 |
|---|---|---|
| bridge `SessionRegistry` 父子图（含 depth/chain） | 1.5-2 d | 中 |
| bridge `handleRuntimeAgentEvent` 子任务路由 + sessionStore 反查 | 1.5-2 d | 中（sessionStore 时序确认） |
| bridge approval 分支迁移（先决修复） | 1 d | 中 |
| bridge lifecycle 透传 + abort 状态机 | 1.5 d | 中（grace/timeout 逻辑要测） |
| ~~bridge announce 回注识别~~（v2.1 删除：让父 run 走主对话流，UX 反而自然） | 0 d | — |
| bridge 上行事件 builder + 下行 schema + 子会话路由 | 1.5 d | 低 |
| bridge late events 处理 + 测试 + 死代码清理 | 1.5 d | 低 |
| ai-gateway 字段扩展 + 下行注入 | 0.5-1 d | 低 |
| skill-server V9 migration + lifecycle 事件落库 + close/abort 改写 | 1.5-2 d | 低 |
| miniapp 嵌套渲染 + lifecycle 终态 + announce 展示 | 1.5-2 d | 中 |
| 联调（含嵌套 / abort / kill / 重启恢复 / late event） | 2-3 d | 中 |
| **合计** | **12-17 d** | |

> 比 v1（7-10 d）增加约 60%。主要增量来自：abort 状态机 + 嵌套支持 + late events + 测试覆盖。v2.1 把 v2 的 announce 回注识别去掉了（伪问题）。

---

## 还需要决策的点

开工前要拍板：

1. **嵌套深度**：UI 是否支持任意层级嵌套？还是限制到 2 层（孙子任务折叠）？
2. **用户能不能从小程序中止运行中的子任务**？要不要支持？怎么暴露？
3. **多个子任务并发**时的排版：堆叠 / 折叠 / 平铺？
4. **子任务的 permission 询问怎么展示**：跟主对话同一处弹，还是显示在 SubtaskBlock 里？
5. **bridge `question_reply` API 留不留**：openclaw 没这能力，但 ai-gateway 可能因兼容性还期望 bridge 支持。建议 fail-closed 但保留 schema。

---

## 关键源码索引

### openclaw 端

| 主题 | 文件 |
|---|---|
| subagent 数据模型 | `src/agents/subagent-registry.types.ts` |
| subagent spawn 入口 | `src/agents/subagent-spawn.ts:675` `spawnSubagentDirect` |
| spawnedBy 写 sessionStore | `src/agents/subagent-spawn.ts:1053-1068` |
| subagent 控制工具 (list/kill/steer) | `src/agents/tools/subagents-tool.ts` |
| subagent 结果回注父对话 | `src/agents/subagent-announce.ts` `runSubagentAnnounceFlow` |
| subagent 嵌套深度 | `src/agents/subagent-spawn.ts:753-771` (callerDepth, maxSpawnDepth) |
| agent 事件 emitter | `src/infra/agent-events.ts` |
| 任务上下文表注册 | `src/agents/agent-command.ts:626` |
| sessionKey 擦除策略 | `src/infra/agent-events.ts:217-227` |
| approval 事件 emit | `src/agents/pi-embedded-subscribe.handlers.tools.ts:1069` |
| lifecycle phase emit (end/error) | `src/agents/agent-command.ts:1087-1190` |
| subagent abort 状态机 | `src/agents/subagent-lifecycle-events.ts` |
| subagent cleanup | `src/agents/subagent-session-cleanup.ts` |
| plugin runtime 类型 | `src/plugins/runtime/types.ts`、`types-core.ts` |
| webchat 渠道判定 | `src/utils/message-channel.ts` `isInternalMessageChannel` |

### 我们这边

| 主题 | 文件 |
|---|---|
| bridge 主类 | `plugins/agent-plugin/plugins/message-bridge-openclaw/src/OpenClawGatewayBridge.ts` |
| bridge 上行事件 builder | `plugins/agent-plugin/plugins/message-bridge-openclaw/src/session/upstreamEvents.ts` |
| bridge 下行 schema | `plugins/agent-plugin/plugins/message-bridge-openclaw/src/contracts/downstream.ts` |
| ai-gateway 协议字段 | `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java:127-133` |
| skill-server q_r/p_r 路由 | `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:213,425` |
| skill-server DB schema | `skill-server/src/main/resources/db/migration/V8__subagent_message_part.sql` |
| miniapp SubtaskBlock 分组 | `skill-miniapp/src/hooks/useSkillStream.ts:519-560` |
| miniapp SubtaskBlock 组件 | `skill-miniapp/src/components/SubtaskBlock.tsx` |

---

## 附录 A：为什么不走"伪装成 webchat"

朴素方案是 bridge 把发给 openclaw 的渠道字段改成 "webchat"，绕开 sessionKey 擦除。这条路**技术可行但代价大**：

`isInternalMessageChannel` 在 openclaw 内部不只是控制 sessionKey 擦除，它还是"自家可信渠道"的总开关，关联：
- 消息投递路径（`agents/command/delivery.ts` 多处）
- 命令鉴权（`auto-reply/command-auth.ts:698` —— 用户随口说 `/config show` 会被当真）
- 配置写权限（`channels/plugins/config-write-policy-shared.ts:183`）
- subagent announce 投递路径（`agents/subagent-announce-origin.ts:67`）
- 内部命令（mcp / plugins / config 子命令的可见性）

伪装等于 bridge 承担"自家网页"的全部副作用，得自己消化或屏蔽这些 —— 复杂度远超直接走透传方案。

而且既然子任务事件本身就带 sessionKey（关键发现 #2），**根本不需要伪装**。

---

## 附录 B：v2 修订说明（Codex gpt-5.5 xhigh 评审反馈）

**修正 3 个错误**：
1. ~~"chat 的 close/abort 级联子任务清理"~~ → 改为"bridge 只清自己侧映射，让 openclaw cleanup 收尾"。避免双删 / 丢 announce。
2. ~~"approval 事件 data 形如 {phase, kind, sessionID, ...}"~~ → 明确写"data **不带** sessionID，session 关联用顶层 evt.sessionKey"。
3. ~~暗示 "subagent 不走 pi-embedded-runner"~~ → 改为"subagent 不走 agent-runner-execution.ts (auto-reply 队列)，但 agent 实际执行**仍走 runEmbeddedPiAgent**"。

**新增 3 个关键章节**：
- §D **late events**：子会话关闭后还来事件的处理
- §E **嵌套 subagent**：depth/chain/cascade kill 的设计
- §C **abort 状态机**：15s grace + timeout + killed 路径

**v2.1 自检修订**：
- 删掉 §B "announce 回注挑战"复杂识别方案 —— 自检源码确认 `inputProvenance` 不经 onAgentEvent 透出，本来识别路就走不通；而且想清楚后这是个伪问题：让 announce 触发的父 run 走普通主对话流，UX 上就是 ChatGPT 风格 Tools 体验（卡片显示过程 + 气泡说总结），反而更自然
- 删掉"inputProvenance spike"待办（已源码验证）

**工作量**：v1 7-10 d → v2 14-19 d → **v2.1 12-17 d**（含测试覆盖与嵌套支持）。

**评分**：Codex 给 v1 B（核心方向成立但低估 announce 回注 / abort 状态机 / 嵌套 / cleanup 竞态）。v2.1 在 v2 基础上简化了 announce 处理。
